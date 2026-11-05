"""Identity helpers for article URLs."""

from __future__ import annotations

import re
from urllib.parse import parse_qs, urlsplit, urlunsplit

from .models import short_hash

_INFO_RE = re.compile(r"/info/(\d+)/(\d+)\.htm")


def article_key(url: str) -> str:
    """Return a key that is identical for the two URL forms of one article."""
    parts = urlsplit(url)
    match = _INFO_RE.search(parts.path)
    if match:
        return f"article:{match.group(1)}:{match.group(2)}"

    if parts.path.lower().endswith(".jsp"):
        query = {key.lower(): value for key, value in parse_qs(parts.query).items()}
        tree = (query.get("wbtreeid") or [""])[0]
        news = (query.get("wbnewsid") or [""])[0]
        if tree and news:
            return f"article:{tree}:{news}"

    clean = urlunsplit((parts.scheme, parts.netloc.lower(), parts.path, parts.query, ""))
    return "url:" + short_hash(clean)


def article_external_id(url: str) -> str:
    """Human-readable per-article id, falling back to a URL hash."""
    key = article_key(url)
    if key.startswith("article:"):
        return key.split(":", 1)[1]
    return key.split(":", 1)[1]
