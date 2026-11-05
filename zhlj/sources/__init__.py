"""Registered sources."""

from __future__ import annotations

from .base import JsonSource, ListSource, SourceResult
from .future_news import YouthLeagueSource
from .gs_notice import GSNoticeSource
from .jwgl_exams import JWGLExamSource
from .nic_notice import NICNoticeSource
from .uc_notice import UCNoticeSource
from .whu_notice import WHUNoticeSource
from .zhlj_api import ZHLJAnnouncementSource
from .zhlj_message import ZHLJMessageSource
from .zhlj_notice import ZHLJNoticeSource

SOURCES = {
    source.name: source
    for source in (
        WHUNoticeSource(),
        UCNoticeSource(),
        NICNoticeSource(),
        GSNoticeSource(),
        YouthLeagueSource(),
        ZHLJAnnouncementSource(),
        ZHLJNoticeSource(),
        ZHLJMessageSource(),
        JWGLExamSource(),
    )
}

__all__ = ["SOURCES", "JsonSource", "ListSource", "SourceResult"]
