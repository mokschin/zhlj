from __future__ import annotations

import json
import os
import tempfile
import unittest
from datetime import date
from pathlib import Path

from zhlj import course, jwgl, session
from zhlj.sources.jwgl_exams import JWGLExamSource
from zhlj.sources.zhlj_message import ZHLJMessageSource
from zhlj.sources.zhlj_notice import ZHLJNoticeSource

FIXTURES = Path(__file__).parent / "fixtures"


def fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


class ZHLJNoticeTests(unittest.TestCase):
    def test_filters_to_requested_departments(self) -> None:
        items = ZHLJNoticeSource().parse(
            fixture("zhlj_notice.json"),
            ZHLJNoticeSource.start_url,
            owner_ids=["1709311821", "1855807458"],
        )
        self.assertEqual(len(items), 2)
        self.assertEqual(items[0].title, "关于开展示例主题党日活动的通知")
        self.assertEqual(items[0].published_at, "2026-11-03")
        self.assertEqual(items[0].url_key, "article:1008:54561")
        self.assertEqual(items[1].raw["ownerName"], "团委")

    def test_without_filter_keeps_all(self) -> None:
        items = ZHLJNoticeSource().parse(
            fixture("zhlj_notice.json"), ZHLJNoticeSource.start_url
        )
        self.assertEqual(len(items), 3)

    def test_rejects_error_payload(self) -> None:
        from zhlj.http import FetchError

        with self.assertRaises(FetchError):
            ZHLJNoticeSource().parse('{"result":0,"msg":"x"}', "https://x")


class ZHLJMessageTests(unittest.TestCase):
    def test_filters_to_course_reminders(self) -> None:
        items = ZHLJMessageSource().parse(
            fixture("zhlj_messages.json"),
            ZHLJMessageSource.start_url,
            source_filter="珞珈在线",
        )
        self.assertEqual(len(items), 2)
        self.assertEqual(items[0].title, "作业:《示例课程A》第一次作业")
        self.assertEqual(items[0].published_at, "2026-11-05")
        self.assertTrue(items[0].url.startswith("https://example.com/"))

    def test_without_filter_keeps_every_message(self) -> None:
        items = ZHLJMessageSource().parse(
            fixture("zhlj_messages.json"), ZHLJMessageSource.start_url
        )
        self.assertEqual(len(items), 3)


class CourseTests(unittest.TestCase):
    def setUp(self) -> None:
        self.terms = json.loads(fixture("zhlj_terms.json"))["data"]
        self.term = course.current_term(self.terms)
        self.data = json.loads(fixture("zhlj_curriculum.json"))["data"]

    def test_current_term(self) -> None:
        self.assertEqual(self.term["yearTermName"], "2026-2027第一学期")
        self.assertEqual(self.term["termCode"], "3")

    def test_week_math(self) -> None:
        self.assertEqual(course.current_week(self.term, date(2026, 11, 5)), 9)
        self.assertEqual(course.week_start_date(self.term, 9), date(2026, 11, 1))
        self.assertEqual(course.api_date_for_week(self.term, 9), date(2026, 11, 2))

    def test_flatten_drops_empty_slots(self) -> None:
        courses = course.flatten(self.data)
        self.assertEqual(len(courses), 3)
        self.assertEqual(courses[0].name, "示例课程A")
        self.assertEqual(courses[0].periods, "第1-3节")
        self.assertEqual(courses[0].weeks, "6-12周")
        self.assertEqual(courses[1].day, 1)
        self.assertEqual(courses[2].day, 4)

    def test_format_week(self) -> None:
        text = course.format_week(self.term, 9, course.flatten(self.data))
        self.assertIn("第 9 周", text)
        self.assertIn("周一 11-02", text)
        self.assertIn("示例课程A", text)
        self.assertIn("1区101", text)
        self.assertIn("周四 11-05", text)

    def test_json_output(self) -> None:
        payload = json.loads(course.to_json(self.term, 9, course.flatten(self.data)))
        self.assertEqual(payload["week"], 9)
        self.assertEqual(payload["weekStart"], "2026-11-01")
        self.assertEqual(len(payload["courses"]), 3)


class ExamTests(unittest.TestCase):
    def setUp(self) -> None:
        self.exams = [
            jwgl.Exam(name="期末考试", course="示例课程A", date="2027-01-10",
                      time="09:00-11:00", room="1区101", campus="示例校区",
                      seat="12", duration="120分钟", college="示例学院"),
            jwgl.Exam(name="期末考试", course="示例课程B", date="2027-01-12",
                      time="14:00-16:00", room="2区202", campus="示例校区",
                      seat="", duration="120分钟", college="示例学院"),
        ]

    def test_format(self) -> None:
        text = jwgl.format_exams(self.exams, {"xnm": "2026", "xqm": "3"})
        self.assertIn("2027-01-10 09:00-11:00", text)
        self.assertIn("示例课程A", text)
        self.assertIn("座位 12", text)

    def test_empty(self) -> None:
        self.assertIn("暂无考试安排", jwgl.format_exams([], {"xnm": "2026", "xqm": "3"}))

    def test_source_makes_unique_keys(self) -> None:
        items = JWGLExamSource().parse(self.exams, {"xnm": "2026", "xqm": "3"})
        self.assertEqual(len(items), 2)
        self.assertNotEqual(items[0].url_key, items[1].url_key)
        self.assertTrue(items[0].url_key.startswith("exam:"))
        self.assertEqual(items[0].published_at, "2027-01-10")

    def test_selected_value(self) -> None:
        html = ('<select id="cx_xnm"><option value="2025">x</option>'
                '<option value="2026" selected>2026-2027</option></select>')
        self.assertEqual(jwgl.selected_value(html, "cx_xnm"), "2026")

    def test_json(self) -> None:
        payload = json.loads(jwgl.exams_to_json(self.exams, {"xnm": "2026"}))
        self.assertEqual(len(payload["exams"]), 2)
        self.assertEqual(payload["exams"][0]["course"], "示例课程A")


class SessionTests(unittest.TestCase):
    def test_roundtrip_and_permissions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "session.json"
            session.save_cookie(path, "Cookie: JSESSIONID=abc; route=xyz\n")
            self.assertEqual(session.load_cookie(path), "JSESSIONID=abc; route=xyz")
            self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
            self.assertEqual(session.mask_cookie("JSESSIONID=abcdef; route=xyz"),
                             "JSESSIONID=abcd***; route=xyz***")

    def test_missing_returns_none(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(session.load_cookie(Path(tmp) / "nope.json"))


if __name__ == "__main__":
    unittest.main()
