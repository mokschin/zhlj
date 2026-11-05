"""Notification backends: desktop bubbles and ntfy."""

from __future__ import annotations

import logging
import shutil
import subprocess
import urllib.error
import urllib.request

from .models import Item

MAX_BODY = 900


def format_message(items: list[Item]) -> tuple[str, str]:
    title = f"智慧珞珈 · {len(items)} 条新公告" if len(items) > 1 else "智慧珞珈 · 1 条新公告"
    lines = []
    for item in items[:8]:
        label = SOURCE_LABELS.get(item.source, item.source)
        date = f"（{item.published_at}）" if item.published_at else ""
        lines.append(f"【{label}】{item.title}{date}")
    if len(items) > 8:
        lines.append(f"……另有 {len(items) - 8} 条")
    body = "\n".join(lines)
    if len(body) > MAX_BODY:
        body = body[: MAX_BODY - 1] + "…"
    return title, body


SOURCE_LABELS = {
    "whu_notice": "武大通知",
    "uc_notice": "本科院",
    "nic_notice": "智慧校园",
    "gs_notice": "研究生院",
    "future_news": "团委",
    "zhlj_api": "智慧珞珈",
    "zhlj_notice": "聚合通知",
    "zhlj_message": "作业提醒",
    "jwgl_exams": "考试安排",
}


def notify(items: list[Item], *, desktop: bool = True, ntfy_url: str = "",
           logger: logging.Logger | None = None) -> bool:
    """Send notifications and return whether at least one channel accepted it."""
    if not items:
        return True
    log = logger or logging.getLogger(__name__)
    title, body = format_message(items)

    delivered = False
    if desktop:
        delivered = _desktop(title, body, log) or delivered
    if ntfy_url:
        delivered = _ntfy(ntfy_url, title, body, log) or delivered
    return delivered


def _desktop(title: str, body: str, log: logging.Logger) -> bool:
    if not shutil.which("notify-send"):
        log.info("notify-send not available; skipping desktop notification")
        return False
    try:
        completed = subprocess.run(
            ["notify-send", "--app-name=智慧珞珈", "--urgency=normal", title, body],
            check=False,
            timeout=10,
        )
        return completed.returncode == 0
    except (OSError, subprocess.SubprocessError) as exc:
        log.warning("desktop notification failed: %s", exc)
        return False


def _ntfy(url: str, title: str, body: str, log: logging.Logger) -> bool:
    request = urllib.request.Request(
        url,
        data=body.encode("utf-8"),
        method="POST",
        headers={
            "Title": "ZHLJ",
            "Priority": "default",
            "Tags": "bell",
            "Content-Type": "text/plain; charset=utf-8",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            response.read()
        log.info("ntfy notification sent to %s", url)
        return True
    except (urllib.error.URLError, OSError) as exc:
        log.warning("ntfy notification failed: %s", exc)
        return False
