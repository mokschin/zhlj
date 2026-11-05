"""Session cookie storage for the authenticated ZHLJ endpoints.

The cookie is a credential: it lives in ``data/session.json`` (mode 0600,
git-ignored), never in config.toml, logs, or shared memory.
"""

from __future__ import annotations

import json
import os
from datetime import datetime
from pathlib import Path


def load_cookie(path: Path) -> str | None:
    env = os.environ.get("ZHLJ_COOKIE", "").strip()
    if env:
        return env
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    cookie = (data.get("cookie") or "").strip()
    return cookie or None


def save_cookie(path: Path, cookie: str) -> None:
    cookie = " ".join(cookie.split())
    if cookie.lower().startswith("cookie:"):
        cookie = cookie[7:].strip()
    if not cookie:
        raise ValueError("empty cookie")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {"cookie": cookie, "updated_at": datetime.now().astimezone().isoformat(timespec="seconds")},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    os.chmod(path, 0o600)


def mask_cookie(cookie: str) -> str:
    parts = []
    for pair in cookie.split(";"):
        pair = pair.strip()
        if not pair:
            continue
        name, _, value = pair.partition("=")
        parts.append(f"{name}={value[:4]}***" if value else name)
    return "; ".join(parts)
