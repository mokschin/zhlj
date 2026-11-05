"""Youth League work updates: https://future.whu.edu.cn/gzdt.htm

The youth league's 通知公告 channel is CAS-protected; 工作动态 is public.
"""

from __future__ import annotations

from ..models import Item
from .base import ListSource
from .html_list import next_page_url, parse_list_page


class YouthLeagueSource(ListSource):
    name = "future_news"
    label = "团委动态"
    start_url = "https://future.whu.edu.cn/gzdt.htm"

    def parse(self, html: str, page_url: str) -> list[Item]:
        return parse_list_page(html, page_url, self.name)

    def next_page(self, html: str, page_url: str) -> str | None:
        return next_page_url(html, page_url)
