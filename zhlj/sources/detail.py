"""Extract the article body and attachments from a Visual SiteBuilder page."""

from __future__ import annotations

import re
from html.parser import HTMLParser
from urllib.parse import urljoin

_ATTACHMENT_RE = re.compile(
    r"\.(?:pdf|docx?|xlsx?|pptx?|zip|rar|7z|txt|csv)$", re.IGNORECASE
)
_BLOCK_TAGS = {"p", "br", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6"}


class _BodyParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.attachments: list[str] = []
        self._div_depth = 0
        self._capture_depth: int | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        if tag == "div":
            self._div_depth += 1
            classes = attributes.get("class") or ""
            if self._capture_depth is None and "v_news_content" in classes:
                self._capture_depth = self._div_depth
        if self._capture_depth is None:
            return
        if tag in _BLOCK_TAGS:
            self.parts.append("\n")
        if tag == "a":
            href = attributes.get("href") or ""
            if href and _ATTACHMENT_RE.search(href.split("?")[0]):
                self.attachments.append(href)

    def handle_endtag(self, tag: str) -> None:
        if tag == "div":
            if self._capture_depth is not None and self._div_depth == self._capture_depth:
                self._capture_depth = None
            self._div_depth = max(0, self._div_depth - 1)
        elif tag in _BLOCK_TAGS and self._capture_depth is not None:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if self._capture_depth is not None:
            self.parts.append(data)

    @property
    def text(self) -> str:
        raw = "".join(self.parts)
        raw = re.sub(r"[ \t\r\f\v]+", " ", raw)
        raw = re.sub(r"\n\s*\n+", "\n\n", raw)
        return raw.strip()


def extract_detail(html: str, page_url: str) -> tuple[str, list[str]]:
    """Return ``(plain_text_body, absolute_attachment_urls)``."""
    parser = _BodyParser()
    parser.feed(html)
    parser.close()

    text = parser.text
    if not text:
        match = re.search(r"vsbcontent_start(.*?)vsbcontent_end", html, re.S)
        if match:
            text = re.sub(r"<[^>]+>", " ", match.group(1))
            text = re.sub(r"\s+", " ", text).strip()

    attachments = []
    for href in parser.attachments:
        absolute = urljoin(page_url, href)
        if absolute not in attachments:
            attachments.append(absolute)
    return text, attachments
