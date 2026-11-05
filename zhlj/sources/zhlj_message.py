"""Homework and course reminders from the ZHLJ message center.

GET /whdxIndex/getMessage?type=1 returns messages from 珞珈在线
(Chaoxing): homework assignments, material posts, progress reminders.
"""

from __future__ import annotations

import json

from ..http import FetchError
from ..models import Item
from .base import JsonSource


class ZHLJMessageSource(JsonSource):
    name = "zhlj_message"
    label = "作业与课程提醒"
    start_url = "https://zhlj.whu.edu.cn/whdxIndex/getMessage?type=1"
    requires_auth = True
    referer = "https://zhlj.whu.edu.cn/pc/toIndex"

    def fetch(self, client, cookie: str) -> str:
        return client.get(
            self.start_url, referer=self.referer, headers={"Cookie": cookie}
        )

    def parse(self, text: str, page_url: str,
              source_filter: str | None = None) -> list[Item]:
        try:
            payload = json.loads(text)
        except json.JSONDecodeError as exc:
            raise FetchError(f"invalid JSON from {page_url}: {exc}") from exc

        if payload.get("result") != 1:
            raise FetchError(f"message API error: {payload.get('msg') or payload}")

        messages = (payload.get("data") or {}).get("dataList") or []
        items: list[Item] = []
        seen: set[str] = set()
        for message in messages:
            source_name = (message.get("source") or "").strip()
            if source_filter and source_name != source_filter:
                continue
            title = (message.get("title") or "").strip()
            if not title:
                continue
            message_id = str(message.get("id") or "")
            url = (message.get("waitLink") or message.get("murl")
                   or message.get("url") or "").strip()
            if not url:
                url = f"https://zhlj.whu.edu.cn/pc/index#message={message_id}"
            item = Item(
                source=self.name,
                external_id=message_id or title,
                title=title,
                url=url,
                published_at=(message.get("time") or "")[:10],
                summary=source_name,
                raw=message,
            )
            if item.url_key in seen:
                continue
            seen.add(item.url_key)
            items.append(item)
        return items
