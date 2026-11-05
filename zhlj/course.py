"""Current-term timetable from the logged-in ZHLJ portal."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from datetime import date, timedelta

from .http import FetchError

TERMS_URL = "https://zhlj.whu.edu.cn/whdxSchedule/getNearSchoolTerm"
CURRICULUM_URL = "https://zhlj.whu.edu.cn/whdxIndex/getCurriculum?date={day}"
REFERER = "https://zhlj.whu.edu.cn/pc/toIndex"
WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]


@dataclass
class Course:
    day: int
    name: str
    teacher: str
    classroom: str
    from_class: int
    to_class: int
    from_week: int
    to_week: int
    course_type: str = ""
    section_plan: str = ""

    @property
    def periods(self) -> str:
        if self.from_class == self.to_class:
            return f"第{self.from_class}节"
        return f"第{self.from_class}-{self.to_class}节"

    @property
    def weeks(self) -> str:
        if self.from_week == self.to_week:
            return f"{self.from_week}周"
        return f"{self.from_week}-{self.to_week}周"


def fetch_terms(client, cookie: str) -> list[dict]:
    text = client.get(TERMS_URL, referer=REFERER, headers={"Cookie": cookie})
    payload = _load(text, TERMS_URL)
    return payload.get("data") or []


def current_term(terms: list[dict]) -> dict:
    for term in terms:
        if term.get("nowTerm") == 1:
            return term
    raise FetchError("没有找到当前学期")


def term_start(term: dict) -> date:
    return date.fromisoformat(term["termStartTime"])


def current_week(term: dict, today: date | None = None) -> int:
    today = today or date.today()
    week = (today - term_start(term)).days // 7 + 1
    return max(1, min(week, int(term.get("termWeekCount") or week)))


def week_start_date(term: dict, week: int) -> date:
    return term_start(term) + timedelta(weeks=week - 1)


def api_date_for_week(term: dict, week: int) -> date:
    start = week_start_date(term, week)
    # termStartTime is usually a Sunday; the portal accepts any day inside
    # the week, and a Monday is the least ambiguous.
    return start + timedelta(days=1) if start.weekday() == 6 else start


def fetch_curriculum(client, cookie: str, day: date) -> list[dict]:
    url = CURRICULUM_URL.format(day=day.isoformat())
    text = client.get(url, referer=REFERER, headers={"Cookie": cookie})
    payload = _load(text, url)
    return payload.get("data") or []


def flatten(data: list[dict]) -> list[Course]:
    courses: list[Course] = []
    for block in data:
        for entry in block.get("curriculumList") or []:
            if not entry.get("hasClass") or not entry.get("name"):
                continue
            courses.append(
                Course(
                    day=int(entry.get("day") or block.get("day") or 0),
                    name=entry["name"],
                    teacher=entry.get("teacher") or "",
                    classroom=entry.get("classroom") or "",
                    from_class=int(entry.get("fromClass") or 0),
                    to_class=int(entry.get("endClass") or entry.get("fromClass") or 0),
                    from_week=int(entry.get("fromWeek") or 0),
                    to_week=int(entry.get("endWeek") or entry.get("fromWeek") or 0),
                    course_type=entry.get("courseTypes") or "",
                    section_plan=entry.get("sectionPlan") or "",
                )
            )
    return sorted(courses, key=lambda c: (c.day, c.from_class))


def format_week(term: dict, week: int, courses: list[Course]) -> str:
    start = week_start_date(term, week)
    end = start + timedelta(days=6)
    # Day numbers are Monday-based even when the term starts on a Sunday.
    base = start + timedelta(days=1) if start.weekday() == 6 else start
    lines = [f"{term['yearTermName']}  第 {week} 周（{start:%m-%d} ~ {end:%m-%d}）"]
    for day in range(1, 8):
        day_courses = [c for c in courses if c.day == day]
        if not day_courses:
            continue
        lines.append("")
        lines.append(f"{WEEKDAYS[day - 1]} {base + timedelta(days=day - 1):%m-%d}")
        for course in day_courses:
            detail = "  ".join(
                part for part in (course.name, course.teacher, course.classroom)
                if part
            )
            lines.append(f"  {course.periods:<8}{detail}  （{course.weeks}）")
    return "\n".join(lines)


def to_json(term: dict, week: int, courses: list[Course]) -> str:
    return json.dumps(
        {
            "term": term,
            "week": week,
            "weekStart": week_start_date(term, week).isoformat(),
            "courses": [asdict(course) for course in courses],
        },
        ensure_ascii=False,
        indent=2,
    )


def _load(text: str, source: str) -> dict:
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as exc:
        raise FetchError(f"invalid JSON from {source}: {exc}") from exc
    if payload.get("result") != 1:
        raise FetchError(
            f"portal error from {source}: {payload.get('msg') or payload.get('code')}"
        )
    return payload
