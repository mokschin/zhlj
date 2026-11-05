"""SQLite storage with cross-source de-duplication."""

from __future__ import annotations

import json
import sqlite3
from datetime import datetime
from pathlib import Path

from .models import Item
from .sources.base import SourceResult

SCHEMA = """
CREATE TABLE IF NOT EXISTS items (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    source        TEXT NOT NULL,
    external_id   TEXT NOT NULL,
    url_key       TEXT NOT NULL UNIQUE,
    title         TEXT NOT NULL,
    url           TEXT NOT NULL,
    published_at  TEXT,
    summary       TEXT,
    content       TEXT,
    attachments   TEXT,
    raw           TEXT,
    first_seen_at TEXT NOT NULL,
    notified_at   TEXT,
    UNIQUE (source, external_id)
);

CREATE INDEX IF NOT EXISTS idx_items_published
    ON items (published_at DESC);
CREATE INDEX IF NOT EXISTS idx_items_source
    ON items (source, first_seen_at DESC);

CREATE TABLE IF NOT EXISTS runs (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    run_at    TEXT NOT NULL,
    source    TEXT NOT NULL,
    ok        INTEGER NOT NULL,
    pages     INTEGER NOT NULL DEFAULT 0,
    fetched   INTEGER NOT NULL DEFAULT 0,
    new_items INTEGER NOT NULL DEFAULT 0,
    error     TEXT
);

CREATE TABLE IF NOT EXISTS source_state (
    source         TEXT PRIMARY KEY,
    initialized_at TEXT NOT NULL,
    last_run_at    TEXT
);
"""


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


class Store:
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(self.path)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.executescript(SCHEMA)
        # Sources that already produced a successful run are past their
        # baseline even if cross-source de-duplication left no rows under
        # their own name.
        self.conn.execute(
            """
            INSERT OR IGNORE INTO source_state (source, initialized_at)
            SELECT DISTINCT source, ? FROM runs WHERE ok = 1
            """,
            (now_iso(),),
        )
        self.conn.commit()

    # ------------------------------------------------------------------
    def insert_items(self, items: list[Item]) -> list[Item]:
        """Insert unseen items and return only those that were new."""
        new_items: list[Item] = []
        now = now_iso()
        with self.conn:
            for item in items:
                row = item.to_row()
                cursor = self.conn.execute(
                    """
                    INSERT OR IGNORE INTO items
                        (source, external_id, url_key, title, url, published_at,
                         summary, content, attachments, raw, first_seen_at)
                    VALUES
                        (:source, :external_id, :url_key, :title, :url, :published_at,
                         :summary, :content, :attachments, :raw, :first_seen_at)
                    """,
                    {**row, "first_seen_at": now},
                )
                if cursor.rowcount:
                    new_items.append(item)
                elif item.content or item.attachments:
                    # Same article seen through another source: keep the extra
                    # body text instead of dropping it with the duplicate.
                    self.conn.execute(
                        """
                        UPDATE items SET
                            content = CASE WHEN content IS NULL OR content = ''
                                           THEN ? ELSE content END,
                            summary = CASE WHEN summary IS NULL OR summary = ''
                                           THEN ? ELSE summary END,
                            attachments = CASE WHEN attachments IS NULL
                                                    OR attachments = '[]'
                                               THEN ? ELSE attachments END
                        WHERE url_key = ?
                        """,
                        (
                            item.content,
                            item.summary,
                            json.dumps(item.attachments, ensure_ascii=False),
                            item.url_key,
                        ),
                    )
        return new_items

    def update_content(self, item: Item, content: str, attachments: list[str]) -> None:
        with self.conn:
            self.conn.execute(
                "UPDATE items SET content=?, attachments=? WHERE url_key=?",
                (content, json.dumps(attachments, ensure_ascii=False), item.url_key),
            )

    def mark_notified(self, items: list[Item]) -> None:
        now = now_iso()
        with self.conn:
            for item in items:
                self.conn.execute(
                    "UPDATE items SET notified_at=? WHERE url_key=?",
                    (now, item.url_key),
                )

    def unnotified(self, limit: int = 100) -> list[Item]:
        rows = self.conn.execute(
            """
            SELECT * FROM items
            WHERE notified_at IS NULL
            ORDER BY COALESCE(published_at, '') DESC, id DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
        items = []
        for row in rows:
            try:
                attachments = json.loads(row["attachments"] or "[]")
            except json.JSONDecodeError:
                attachments = []
            items.append(
                Item(
                    source=row["source"],
                    external_id=row["external_id"],
                    title=row["title"],
                    url=row["url"],
                    published_at=row["published_at"] or "",
                    summary=row["summary"] or "",
                    content=row["content"] or "",
                    attachments=attachments,
                )
            )
        return items

    def is_empty(self, source: str) -> bool:
        row = self.conn.execute(
            "SELECT 1 FROM items WHERE source=? LIMIT 1", (source,)
        ).fetchone()
        return row is None

    def is_first_run(self, source: str) -> bool:
        row = self.conn.execute(
            "SELECT 1 FROM source_state WHERE source=?", (source,)
        ).fetchone()
        return row is None

    def mark_initialized(self, source: str) -> None:
        with self.conn:
            self.conn.execute(
                """
                INSERT INTO source_state (source, initialized_at, last_run_at)
                VALUES (?, ?, ?)
                ON CONFLICT(source) DO UPDATE SET last_run_at = excluded.last_run_at
                """,
                (source, now_iso(), now_iso()),
            )

    def record_run(self, result: SourceResult) -> None:
        with self.conn:
            self.conn.execute(
                """
                INSERT INTO runs (run_at, source, ok, pages, fetched, new_items, error)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    now_iso(),
                    result.source,
                    1 if result.ok else 0,
                    result.pages,
                    result.fetched,
                    len(result.new_items),
                    result.error,
                ),
            )

    # ------------------------------------------------------------------
    def stats(self) -> list[sqlite3.Row]:
        return list(
            self.conn.execute(
                """
                SELECT source,
                       COUNT(*) AS total,
                       MAX(published_at) AS latest,
                       SUM(CASE WHEN notified_at IS NULL THEN 1 ELSE 0 END) AS unnotified
                FROM items GROUP BY source ORDER BY source
                """
            )
        )

    def recent_runs(self, limit: int = 10) -> list[sqlite3.Row]:
        return list(
            self.conn.execute(
                "SELECT * FROM runs ORDER BY id DESC LIMIT ?", (limit,)
            )
        )

    def close(self) -> None:
        self.conn.close()
