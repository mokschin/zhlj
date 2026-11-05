"""Graduate school notices: https://gs.whu.edu.cn/tzgg/qb.htm"""

from __future__ import annotations

from ..models import Item
from .base import ListSource
from .html_list import next_page_url, parse_list_page


class GSNoticeSource(ListSource):
    name = "gs_notice"
    label = "研究生院"
    start_url = "https://gs.whu.edu.cn/tzgg/qb.htm"

    def parse(self, html: str, page_url: str) -> list[Item]:
        return parse_list_page(html, page_url, self.name)

    def next_page(self, html: str, page_url: str) -> str | None:
        return next_page_url(html, page_url)
