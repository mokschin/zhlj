"""Parsing helpers for the Visual SiteBuilder list pages used by WHU sites."""

from __future__ import annotations

import re
from html.parser import HTMLParser
from urllib.parse import urljoin

from ..ids import article_external_id
from ..models import Item

_ARTICLE_HREF_RE = re.compile(
    r"(?:^|/)info/\d+/\d+\.htm|(?:tzggnr|content)\.jsp"
)
_NEXT_RE = re.compile(
    r'<span[^>]*class="[^"]*\bp_next\b[^"]*"[^>]*>\s*<a[^>]*href="([^"]+)"',
    re.IGNORECASE,
)
_NEXT_TEXT_RE = re.compile(
    r'<a[^>]*href="([^"]+)"[^>]*>\s*(?:下一页|下页)\s*</a>', re.IGNORECASE
)
_DATE_PATTERNS = [
    (re.compile(r"(20\d{2})[-./年](\d{1,2})[-./月](\d{1,2})"), ("y", "m", "d")),
    (re.compile(r"(20\d{2})\s+(\d{1,2})-(\d{1,2})"), ("y", "m", "d")),
    (re.compile(r"(\d{1,2})\s+(20\d{2})\.(\d{1,2})"), ("d", "y", "m")),
]
_DATE_FRAGMENTS = [
    re.compile(r"20\d{2}\s*[-./年]\s*\d{1,2}\s*[-./月]\s*\d{1,2}\s*日?"),
    re.compile(r"20\d{2}\s+\d{1,2}-\d{1,2}"),
    re.compile(r"\b\d{1,2}\s+20\d{2}\.\d{1,2}\b"),
]
_HEADING_TAGS = {"h1", "h2", "h3", "h4", "h5", "h6"}


class _Collector(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[dict] = []
        self.lis: list[dict] = []
        self._row: dict | None = None
        self._cell: dict | None = None
        self._anchor: dict | None = None
        self._li: dict | None = None
        self._heading: dict | None = None

    # -- text routing --------------------------------------------------
    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        if tag == "tr":
            self._row = {"text": [], "cells": [], "anchors": []}
        elif tag in ("td", "th"):
            self._cell = {"text": [], "anchors": []}
        elif tag == "a":
            self._anchor = {
                "href": attributes.get("href") or "",
                "title": attributes.get("title") or "",
                "text": [],
                "heading": "",
            }
        elif tag == "li":
            self._li = {"text": [], "anchors": []}
        elif tag in _HEADING_TAGS and self._anchor is not None:
            self._heading = {"text": []}

    def handle_data(self, data: str) -> None:
        for node in (self._row, self._cell, self._anchor, self._li, self._heading):
            if node is not None:
                node["text"].append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag in _HEADING_TAGS and self._heading is not None:
            if self._anchor is not None:
                self._anchor["heading"] = _clean("".join(self._heading["text"]))
            self._heading = None
        elif tag == "a" and self._anchor is not None:
            self._anchor["text"] = _clean("".join(self._anchor["text"]))
            if self._cell is not None:
                self._cell["anchors"].append(self._anchor)
            if self._li is not None:
                self._li["anchors"].append(self._anchor)
            if self._row is not None:
                self._row["anchors"].append(self._anchor)
            self._anchor = None
        elif tag in ("td", "th") and self._cell is not None:
            if self._row is not None:
                self._row["cells"].append(
                    {"text": _clean("".join(self._cell["text"])), "anchors": self._cell["anchors"]}
                )
            self._cell = None
        elif tag == "tr" and self._row is not None:
            self.rows.append(self._row)
            self._row = None
        elif tag == "li" and self._li is not None:
            self.lis.append(self._li)
            self._li = None


def parse_list_page(html: str, page_url: str, source: str) -> list[Item]:
    collector = _Collector()
    collector.feed(html)
    collector.close()

    found: dict[str, Item] = {}

    for row in collector.rows:
        date = _find_date(row["text"])
        anchor = _first_article_anchor(row["anchors"])
        if date and anchor:
            _remember(found, source, page_url, anchor, row["text"], date)

    for li in collector.lis:
        date = _find_date(li["text"])
        anchor = _first_article_anchor(li["anchors"])
        if date and anchor:
            _remember(found, source, page_url, anchor, li["text"], date)

    return list(found.values())


def next_page_url(html: str, page_url: str) -> str | None:
    for pattern in (_NEXT_RE, _NEXT_TEXT_RE):
        match = pattern.search(html)
        if match:
            href = match.group(1).strip()
            if href and not href.lower().startswith(("javascript:", "#")):
                return urljoin(page_url, href)
    return None


# ----------------------------------------------------------------------
def _remember(found: dict[str, Item], source: str, page_url: str,
              anchor: dict, texts: list[str], date: str) -> None:
    url = urljoin(page_url, anchor["href"])
    if url in found:
        return
    title = _clean(anchor.get("title") or "")
    if not title:
        title = _clean(anchor.get("heading") or "")
    if not title:
        title = _clean(anchor.get("text") or "")
    if not title:
        title = _clean(" ".join(texts))
    title = _strip_date(title)
    if not title:
        return
    found[url] = Item(
        source=source,
        external_id=article_external_id(url),
        title=title,
        url=url,
        published_at=date,
    )


def _first_article_anchor(anchors: list[dict]) -> dict | None:
    for anchor in anchors:
        if anchor["href"] and _ARTICLE_HREF_RE.search(anchor["href"]):
            return anchor
    return None


def _find_date(texts: list[str]) -> str:
    text = _clean(" ".join(texts))
    for pattern, order in _DATE_PATTERNS:
        match = pattern.search(text)
        if not match:
            continue
        values = dict(zip(order, (int(part) for part in match.groups())))
        year, month, day = values["y"], values["m"], values["d"]
        if 1 <= month <= 12 and 1 <= day <= 31:
            return f"{year:04d}-{month:02d}-{day:02d}"
    return ""


def _strip_date(text: str) -> str:
    for pattern in _DATE_FRAGMENTS:
        text = pattern.sub(" ", text)
    return _clean(text)


def _clean(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()
