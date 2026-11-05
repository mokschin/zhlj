"""Smart Campus Department notices: https://nic.whu.edu.cn/szwd/news/tzgg.htm"""

from __future__ import annotations

from ..models import Item
from .base import ListSource
from .html_list import next_page_url, parse_list_page


class NICNoticeSource(ListSource):
    name = "nic_notice"
    label = "智慧校园部"
    start_url = "https://nic.whu.edu.cn/szwd/news/tzgg.htm"

    def parse(self, html: str, page_url: str) -> list[Item]:
        return parse_list_page(html, page_url, self.name)

    def next_page(self, html: str, page_url: str) -> str | None:
        return next_page_url(html, page_url)
