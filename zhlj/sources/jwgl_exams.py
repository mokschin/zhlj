"""Exam arrangements from the undergraduate academic system."""

from __future__ import annotations

from dataclasses import asdict

from ..jwgl import EXAM_PAGE, Exam, fetch_exams
from ..models import Item, short_hash
from .base import JsonSource


class JWGLExamSource(JsonSource):
    name = "jwgl_exams"
    label = "考试安排"
    start_url = EXAM_PAGE
    requires_auth = True
    session_name = "jwgl"

    def fetch(self, client, cookie: str, xnm: str | None = None,
              xqm: str | None = None):
        return fetch_exams(client, cookie, xnm=xnm, xqm=xqm)

    def parse(self, exams: list[Exam], term: dict) -> list[Item]:
        items: list[Item] = []
        for exam in exams:
            key = "exam:" + short_hash(
                "|".join(
                    (exam.name, exam.course, exam.date, exam.time, exam.room, exam.seat)
                )
            )
            title = " ".join(part for part in (exam.course, exam.name) if part)
            summary = " ".join(
                part for part in (exam.date, exam.time, exam.campus, exam.room) if part
            )
            items.append(
                Item(
                    source=self.name,
                    external_id=key,
                    title=title or "考试安排",
                    url=self.start_url,
                    published_at=exam.date,
                    summary=summary,
                    raw=asdict(exam),
                    key=key,
                )
            )
        return items
