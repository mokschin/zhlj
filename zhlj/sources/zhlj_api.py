"""ZHLJ public announcements JSON: /welcome/api/news/announcements"""

from __future__ import annotations

import json
import re

from ..http import FetchError
from ..ids import article_external_id
from ..models import Item
from .base import JsonSource

_TAG_RE = re.compile(r"<[^>]+>")


class ZHLJAnnouncementSource(JsonSource):
    name = "zhlj_api"
    label = "智慧珞珈公告"
    start_url = "https://zhlj.whu.edu.cn/welcome/api/news/announcements"

    def parse(self, text: str, page_url: str) -> list[Item]:
        try:
            payload = json.loads(text)
        except json.JSONDecodeError as exc:
            raise FetchError(f"invalid JSON from {page_url}: {exc}") from exc

        if payload.get("code") != 200:
            raise FetchError(
                f"API error {payload.get('code')}: {payload.get('msg') or payload.get('message')}"
            )

        items: list[Item] = []
        seen: set[str] = set()
        for record in payload.get("data") or []:
            url = (record.get("url") or "").strip()
            title = (record.get("title") or "").strip()
            if not url or not title:
                continue
            body = _strip_html(record.get("content") or record.get("wbcontent") or "")
            published = (record.get("showDate") or record.get("date") or "")[:10]
            external = f"{record.get('treeid') or '0'}:{record.get('id') or article_external_id(url)}"
            item = Item(
                source=self.name,
                external_id=external,
                title=title,
                url=url,
                published_at=published,
                summary=body[:200],
                content=body[:8000],
                raw=record,
            )
            if item.url_key in seen:
                continue
            seen.add(item.url_key)
            items.append(item)
        return items


def _strip_html(text: str) -> str:
    return re.sub(r"\s+", " ", _TAG_RE.sub(" ", text)).strip()
