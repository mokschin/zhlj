"""Source interfaces."""

from __future__ import annotations

from dataclasses import dataclass, field

from ..models import Item


@dataclass
class SourceResult:
    source: str
    pages: int = 0
    fetched: int = 0
    new_items: list[Item] = field(default_factory=list)
    error: str | None = None

    @property
    def ok(self) -> bool:
        return self.error is None


class ListSource:
    kind = "list"
    name = ""
    label = ""
    start_url = ""
    requires_auth = False
    session_name = "zhlj"

    def parse(self, html: str, page_url: str) -> list[Item]:
        raise NotImplementedError

    def next_page(self, html: str, page_url: str) -> str | None:
        raise NotImplementedError


class JsonSource:
    kind = "json"
    name = ""
    label = ""
    start_url = ""
    requires_auth = False
    session_name = "zhlj"

    def parse(self, text: str, page_url: str) -> list[Item]:
        raise NotImplementedError
