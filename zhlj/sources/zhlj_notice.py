"""Aggregated notice feed from the logged-in ZHLJ portal.

POST /whdxIndex/getNotice  {"start": 1, "count": 50}
returns the user's subscribed notices across departments, including
组织部 and 团委 whose own list pages are CAS-protected.
"""

from __future__ import annotations

import json
import re

from ..http import FetchError
from ..ids import article_external_id
from ..models import Item
from .base import JsonSource

_TAG_RE = re.compile(r"<[^>]+>")

OWNER_NAMES = {
    "1775598370": "智慧校园部",
    "1855807458": "团委",
    "1602338758": "研究生院",
    "1709311821": "组织部",
    "1407739091": "本科生院",
    "1454705279": "学工部",
    "1568017242": "党政办",
}


class ZHLJNoticeSource(JsonSource):
    name = "zhlj_notice"
    label = "珞珈聚合通知"
    start_url = "https://zhlj.whu.edu.cn/whdxIndex/getNotice"
    requires_auth = True
    referer = "https://zhlj.whu.edu.cn/pc/toIndex"

    def fetch(self, client, cookie: str, count: int = 50) -> str:
        return client.post_json(
            self.start_url,
            {"start": 1, "count": count},
            referer=self.referer,
            headers={"Cookie": cookie},
        )

    def parse(self, text: str, page_url: str,
              owner_ids: list[str] | None = None) -> list[Item]:
        try:
            payload = json.loads(text)
        except json.JSONDecodeError as exc:
            raise FetchError(f"invalid JSON from {page_url}: {exc}") from exc

        if payload.get("result") != 1:
            raise FetchError(f"portal error: {payload.get('msg') or payload}")

        wanted = set(owner_ids or [])
        items: list[Item] = []
        seen: set[str] = set()
        for record in payload.get("data") or []:
            owner = str(record.get("ownerId") or "")
            if wanted and owner not in wanted:
                continue
            url = (record.get("linkUrl") or "").strip()
            title = (record.get("title") or "").strip()
            if not url or not title:
                continue
            body = _strip_html(record.get("content") or "")
            item = Item(
                source=self.name,
                external_id=article_external_id(url),
                title=title,
                url=url,
                published_at=(record.get("showDateStr") or "")[:10],
                summary=body[:200],
                content=body[:8000],
                raw={
                    "ownerId": owner,
                    "ownerName": OWNER_NAMES.get(owner, ""),
                },
            )
            if item.url_key in seen:
                continue
            seen.add(item.url_key)
            items.append(item)
        return items


def _strip_html(text: str) -> str:
    return re.sub(r"\s+", " ", _TAG_RE.sub(" ", text)).strip()
