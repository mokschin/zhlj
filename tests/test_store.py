from __future__ import annotations

import contextlib
import io
import tempfile
import unittest
from pathlib import Path

from zhlj.config import Config
from zhlj.models import Item
from zhlj.run import _fetch_source
from zhlj.sources import SourceResult
from zhlj.sources.zhlj_api import ZHLJAnnouncementSource
from zhlj.store import Store

FIXTURES = Path(__file__).parent / "fixtures"


def item(source: str, external_id: str, url: str, title: str = "t") -> Item:
    return Item(source=source, external_id=external_id, title=title, url=url)


class StoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.tmp.name) / "test.db")

    def tearDown(self) -> None:
        self.store.close()
        self.tmp.cleanup()

    def test_insert_is_idempotent(self) -> None:
        first = item("whu_notice", "5231:1", "https://www.whu.edu.cn/info/5231/1.htm")
        self.assertEqual(len(self.store.insert_items([first])), 1)
        self.assertEqual(len(self.store.insert_items([first])), 0)

    def test_cross_source_dedupe_uses_article_key(self) -> None:
        a = item("whu_notice", "5231:1", "https://www.whu.edu.cn/info/5231/1.htm")
        b = item(
            "zhlj_api", "5231:1",
            "https://www.whu.edu.cn/new2024/tzggnr.jsp?"
            "urltype=news.NewsContentUrl&wbtreeid=5231&wbnewsid=1",
        )
        self.assertEqual(len(self.store.insert_items([a])), 1)
        self.assertEqual(len(self.store.insert_items([b])), 0)

    def test_duplicate_from_other_source_enriches_body(self) -> None:
        base = "https://www.whu.edu.cn/info/5231/1.htm"
        first = item("whu_notice", "5231:1", base)
        richer = Item(
            source="zhlj_api", external_id="5231:1", title="t", url=base,
            summary="摘要", content="正文内容",
        )
        self.assertEqual(len(self.store.insert_items([first])), 1)
        self.assertEqual(self.store.insert_items([richer]), [])
        row = self.store.conn.execute(
            "SELECT content, summary FROM items WHERE url_key=?", (first.url_key,)
        ).fetchone()
        self.assertEqual(row["content"], "正文内容")
        self.assertEqual(row["summary"], "摘要")

    def test_mark_notified_and_stats(self) -> None:
        a = item("whu_notice", "5231:1", "https://www.whu.edu.cn/info/5231/1.htm")
        self.store.insert_items([a])
        self.store.mark_notified([a])
        row = self.store.stats()[0]
        self.assertEqual(row["total"], 1)
        self.assertEqual(row["unnotified"], 0)

    def test_unnotified_returns_only_pending(self) -> None:
        a = item("whu_notice", "5231:1", "https://www.whu.edu.cn/info/5231/1.htm")
        b = item("whu_notice", "5231:2", "https://www.whu.edu.cn/info/5231/2.htm")
        self.store.insert_items([a, b])
        self.store.mark_notified([a])
        pending = self.store.unnotified()
        self.assertEqual([i.title for i in pending], [b.title])
        self.assertEqual(pending[0].url, b.url)

    def test_record_run(self) -> None:
        result = SourceResult(source="whu_notice", pages=1, fetched=10)
        self.store.record_run(result)
        run = self.store.recent_runs(1)[0]
        self.assertEqual(run["source"], "whu_notice")
        self.assertEqual(run["ok"], 1)


class FakeClient:
    def __init__(self, payload: str) -> None:
        self.payload = payload
        self.calls: list[str] = []

    def get(self, url: str, *, referer: str | None = None) -> str:
        self.calls.append(url)
        return self.payload


class FirstRunTests(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.tmp.name) / "test.db")
        self.config = Config(
            data_dir=Path(self.tmp.name),
            fetch_details=False,
            first_run_notify=False,
        )

    def tearDown(self) -> None:
        self.store.close()
        self.tmp.cleanup()

    def test_first_run_records_but_suppresses_notification(self) -> None:
        client = FakeClient((FIXTURES / "zhlj_announcements.json").read_text(encoding="utf-8"))
        result = _fetch_source(
            ZHLJAnnouncementSource(), client, self.store, self.config,
            max_pages=1, dry_run=False,
        )
        self.assertEqual(result.fetched, 6)
        self.assertEqual(result.new_items, [])
        self.assertFalse(self.store.is_empty("zhlj_api"))
        self.assertFalse(self.store.is_first_run("zhlj_api"))

    def test_second_run_marks_new_items_for_notification(self) -> None:
        payload = (FIXTURES / "zhlj_announcements.json").read_text(encoding="utf-8")
        client = FakeClient(payload)
        _fetch_source(ZHLJAnnouncementSource(), client, self.store, self.config,
                      max_pages=1, dry_run=False)
        # Pretend only one of the announcements is new.
        self.store.conn.execute("DELETE FROM items WHERE title != ?", ("珞珈讲坛第551讲预告",))
        self.store.conn.commit()
        result = _fetch_source(ZHLJAnnouncementSource(), client, self.store, self.config,
                               max_pages=1, dry_run=False)
        self.assertEqual(len(result.new_items), 5)

    def test_dry_run_writes_nothing(self) -> None:
        client = FakeClient((FIXTURES / "zhlj_announcements.json").read_text(encoding="utf-8"))
        with contextlib.redirect_stdout(io.StringIO()):
            result = _fetch_source(
                ZHLJAnnouncementSource(), client, self.store, self.config,
                max_pages=1, dry_run=True,
            )
        self.assertEqual(result.fetched, 6)
        self.assertTrue(self.store.is_empty("zhlj_api"))


if __name__ == "__main__":
    unittest.main()
