"""Configuration loading."""

from __future__ import annotations

import tomllib
from dataclasses import dataclass, field
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG_PATH = PROJECT_ROOT / "config.toml"

DEFAULT_SOURCES = [
    "whu_notice",
    "uc_notice",
    "nic_notice",
    "gs_notice",
    "future_news",
    "zhlj_api",
    "zhlj_notice",
    "zhlj_message",
    "jwgl_exams",
]

DEFAULT_OWNER_IDS = ["1775598370", "1855807458", "1602338758", "1709311821"]


@dataclass
class Config:
    data_dir: Path = PROJECT_ROOT / "data"
    max_pages: int = 2
    interval_seconds: float = 3.0
    jitter_seconds: float = 2.0
    timeout_seconds: float = 25.0
    first_run_notify: bool = False
    fetch_details: bool = True
    max_details_per_run: int = 5
    sources: list[str] = field(default_factory=lambda: list(DEFAULT_SOURCES))
    zhlj_owner_ids: list[str] = field(default_factory=lambda: list(DEFAULT_OWNER_IDS))
    zhlj_notice_count: int = 50
    zhlj_message_filter: str = "珞珈在线"
    desktop_notify: bool = True
    ntfy_url: str = ""

    @property
    def db_path(self) -> Path:
        return self.data_dir / "zhlj.db"

    @property
    def log_path(self) -> Path:
        return self.data_dir / "zhlj.log"

    @property
    def session_path(self) -> Path:
        return self.session_path_for("zhlj")

    def session_path_for(self, name: str) -> Path:
        if name == "zhlj":
            return self.data_dir / "session.json"
        return self.data_dir / f"session_{name}.json"

    @classmethod
    def load(cls, path: str | Path | None = None) -> "Config":
        config_path = Path(path) if path else DEFAULT_CONFIG_PATH
        raw: dict = {}
        if config_path.exists():
            with config_path.open("rb") as handle:
                raw = tomllib.load(handle)

        general = raw.get("general", {})
        notify = raw.get("notify", {})
        zhlj = raw.get("zhlj", {})
        data_dir = Path(general.get("data_dir", "data"))
        if not data_dir.is_absolute():
            data_dir = PROJECT_ROOT / data_dir

        return cls(
            data_dir=data_dir,
            max_pages=int(general.get("max_pages", cls.max_pages)),
            interval_seconds=float(general.get("interval_seconds", cls.interval_seconds)),
            jitter_seconds=float(general.get("jitter_seconds", cls.jitter_seconds)),
            timeout_seconds=float(general.get("timeout_seconds", cls.timeout_seconds)),
            first_run_notify=bool(general.get("first_run_notify", cls.first_run_notify)),
            fetch_details=bool(general.get("fetch_details", cls.fetch_details)),
            max_details_per_run=int(
                general.get("max_details_per_run", cls.max_details_per_run)
            ),
            sources=list(general.get("sources", DEFAULT_SOURCES)),
            zhlj_owner_ids=[str(v) for v in zhlj.get("owner_ids", DEFAULT_OWNER_IDS)],
            zhlj_notice_count=int(zhlj.get("notice_count", cls.zhlj_notice_count)),
            zhlj_message_filter=str(
                zhlj.get("message_filter", cls.zhlj_message_filter) or ""
            ),
            desktop_notify=bool(notify.get("desktop", cls.desktop_notify)),
            ntfy_url=str(notify.get("ntfy_url", cls.ntfy_url) or ""),
        )
