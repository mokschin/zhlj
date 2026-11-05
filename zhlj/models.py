"""Data model shared by all sources."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Item:
    source: str
    external_id: str
    title: str
    url: str
    published_at: str = ""
    summary: str = ""
    content: str = ""
    attachments: list[str] = field(default_factory=list)
    raw: dict | None = None
    key: str = ""

    @property
    def url_key(self) -> str:
        """Stable identity used to de-duplicate across sources.

        The same announcement is reachable both as ``/info/<tree>/<id>.htm``
        and as ``new2024/tzggnr.jsp?...&wbnewsid=<id>``; both map to one key.
        Items that share a URL (e.g. rows on one exam page) can set ``key``.
        """
        if self.key:
            return self.key
        from .ids import article_key

        return article_key(self.url)

    def to_row(self) -> dict:
        return {
            "source": self.source,
            "external_id": self.external_id,
            "url_key": self.url_key,
            "title": self.title,
            "url": self.url,
            "published_at": self.published_at,
            "summary": self.summary,
            "content": self.content,
            "attachments": json.dumps(self.attachments, ensure_ascii=False),
            "raw": json.dumps(self.raw, ensure_ascii=False) if self.raw else None,
        }


def short_hash(text: str, length: int = 16) -> str:
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:length]
