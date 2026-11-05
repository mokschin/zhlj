"""Undergraduate academic system (jwgl, ZFsoft) exams and grades."""

from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass

from .http import FetchError

INDEX_URL = "https://jwgl.whu.edu.cn/xtgl/index_initMenu.html?jsdm=xs"
EXAM_PAGE = (
    "https://jwgl.whu.edu.cn/kwgl/kscx_cxXsksxxIndex.html"
    "?gnmkdm=N358105&layout=default"
)
EXAM_QUERY = "https://jwgl.whu.edu.cn/kwgl/kscx_cxXsksxxIndex.html?doType=query"
GRADE_PAGE = (
    "https://jwgl.whu.edu.cn/cjcx/cjcx_cxDgXscj.html"
    "?gnmkdm=N305005&layout=default"
)
GRADE_QUERY = "https://jwgl.whu.edu.cn/cjcx/cjcx_cxXsgrcj.html?doType=query"


class CaptchaRequired(FetchError):
    """The grade endpoint refuses the query without a Dingxiang captcha."""


@dataclass
class Exam:
    name: str
    course: str
    date: str
    time: str
    room: str
    campus: str
    seat: str
    duration: str
    college: str


def fetch_exams(client, cookie: str, xnm: str | None = None,
                xqm: str | None = None) -> tuple[list[Exam], dict]:
    html = client.get(EXAM_PAGE, referer=INDEX_URL, headers={"Cookie": cookie})
    _assert_logged_in(html)

    xnm = xnm or selected_value(html, "cx_xnm")
    xqm = xqm or selected_value(html, "cx_xqm")
    if not xnm or not xqm:
        raise FetchError("无法从考试页解析学年/学期")

    text = client.post_form(
        EXAM_QUERY,
        _query_params(xnm, xqm),
        referer=EXAM_PAGE,
        headers={"Cookie": cookie},
    )
    payload = _load_json(text)
    exams = [
        Exam(
            name=row.get("ksmc") or "",
            course=row.get("kcmc") or "",
            date=row.get("ksrq") or "",
            time=row.get("kssj") or "",
            room=row.get("cdmc") or "",
            campus=row.get("xqmc") or "",
            seat=row.get("zwh") or "",
            duration=row.get("kssc") or "",
            college=row.get("kkxy") or "",
        )
        for row in payload.get("items") or []
    ]
    return exams, {"xnm": xnm, "xqm": xqm, "total": payload.get("totalResult")}


def fetch_grades(client, cookie: str, xnm: str | None = None,
                 xqm: str | None = None) -> tuple[list[dict], dict]:
    html = client.get(GRADE_PAGE, referer=INDEX_URL, headers={"Cookie": cookie})
    _assert_logged_in(html)

    xnm = xnm or selected_value(html, "xnm")
    xqm = xqm or selected_value(html, "xqm")
    if not xnm or not xqm:
        raise FetchError("无法从成绩页解析学年/学期")

    params = _query_params(xnm, xqm, student=True)
    text = client.post_form(
        GRADE_QUERY, params, referer=GRADE_PAGE, headers={"Cookie": cookie}
    )
    if "please try it again" in text:
        raise CaptchaRequired("教务系统要求验证码，无法后台查询成绩")
    payload = _load_json(text)
    if payload.get("state") == "fail":
        raise CaptchaRequired(payload.get("message") or "成绩查询被拒绝")
    return payload.get("items") or [], {"xnm": xnm, "xqm": xqm}


def format_exams(exams: list[Exam], term: dict) -> str:
    if not exams:
        return f"{term.get('xnm', '')}-{term.get('xqm', '')} 暂无考试安排"
    lines = []
    for exam in sorted(exams, key=lambda item: (item.date, item.time)):
        when = " ".join(part for part in (exam.date, exam.time) if part)
        where = " ".join(part for part in (exam.campus, exam.room) if part)
        title = " ".join(part for part in (exam.course, exam.name) if part)
        extra = []
        if exam.seat:
            extra.append(f"座位 {exam.seat}")
        if exam.duration:
            extra.append(f"时长 {exam.duration}")
        lines.append(f"{when}  {title}  {where}"
                     + (f"  （{'，'.join(extra)}）" if extra else ""))
    return "\n".join(lines)


def exams_to_json(exams: list[Exam], term: dict) -> str:
    return json.dumps(
        {"term": term, "exams": [asdict(exam) for exam in exams]},
        ensure_ascii=False,
        indent=2,
    )


def format_grades(items: list[dict], term: dict) -> str:
    if not items:
        return f"{term.get('xnm', '')}-{term.get('xqm', '')} 暂无成绩记录"
    lines = []
    for row in items:
        course = row.get("kcmc") or row.get("kcbmc") or ""
        score = row.get("cj") or row.get("zcj") or ""
        credit = row.get("xf") or ""
        gpa = row.get("jd") or ""
        parts = [course, f"成绩 {score}" if score else ""]
        if credit:
            parts.append(f"学分 {credit}")
        if gpa:
            parts.append(f"绩点 {gpa}")
        lines.append("  ".join(part for part in parts if part))
    return "\n".join(lines)


def selected_value(html: str, element_id: str) -> str:
    import re

    match = re.search(
        rf'id="{re.escape(element_id)}".*?<option[^>]*value="([^"]*)"[^>]*selected',
        html,
        re.S,
    )
    return match.group(1) if match else ""


def _query_params(xnm: str, xqm: str, student: bool = False) -> dict:
    params = {
        "xnm": xnm,
        "xqm": xqm,
        "_search": "false",
        "nd": str(int(time.time() * 1000)),
        "queryModel.showCount": "100",
        "queryModel.currentPage": "1",
        "queryModel.sortName": "",
        "queryModel.sortOrder": "asc",
        "time": "1",
    }
    if student:
        params.update({"sy_id": "", "sq_id": ""})
    else:
        params.update({"ksmcdmb_id": "", "kch": "", "kc": "", "ksrq": "", "kkbm_id": ""})
    return params


def _assert_logged_in(html: str) -> None:
    if "统一身份认证" in html or "authserver" in html:
        raise FetchError("jwgl 会话已失效，请重新 --set-cookie --session jwgl")


def _load_json(text: str) -> dict:
    if "please try it again" in text:
        raise CaptchaRequired("教务系统要求验证码")
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise FetchError(f"jwgl 返回了非 JSON 内容: {text[:120]}") from exc
