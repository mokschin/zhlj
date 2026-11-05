"""Fetch orchestration and CLI entry point."""

from __future__ import annotations

import argparse
import json
import logging
import logging.handlers
import sys
from dataclasses import replace
from pathlib import Path

from . import course, jwgl, session
from .config import Config
from .http import BlockedError, FetchError, HttpClient
from .models import Item
from .notify import notify
from .sources import SOURCES, JsonSource, ListSource, SourceResult
from .sources.detail import extract_detail
from .sources.jwgl_exams import JWGLExamSource
from .sources.zhlj_message import ZHLJMessageSource
from .sources.zhlj_notice import ZHLJNoticeSource
from .store import Store

log = logging.getLogger("zhlj")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="zhlj", description="智慧珞珈公告抓取")
    parser.add_argument("--config", type=Path, help="配置文件路径")
    parser.add_argument("--source", action="append", choices=sorted(SOURCES),
                        help="只抓指定来源，可重复")
    parser.add_argument("--max-pages", type=int, help="每个列表源最多抓几页")
    parser.add_argument("--backfill", action="store_true",
                        help="补历史：抓取 max-pages 页且不发通知")
    parser.add_argument("--dry-run", action="store_true", help="只抓取并打印，不写库")
    parser.add_argument("--no-notify", action="store_true", help="不发送通知")
    parser.add_argument("--no-details", action="store_true", help="不抓取详情正文")
    parser.add_argument("--test-notify", action="store_true", help="发送一条测试通知后退出")
    parser.add_argument("--status", action="store_true", help="查看库内统计后退出")
    parser.add_argument("--set-cookie", action="store_true",
                        help="从标准输入读取 Cookie 并保存")
    parser.add_argument("--session", choices=["zhlj", "jwgl"], default="zhlj",
                        help="配合 --set-cookie 选择服务（默认 zhlj）")
    parser.add_argument("--check-session", action="store_true",
                        help="检查登录会话是否有效")
    parser.add_argument("--courses", action="store_true", help="打印本周课表")
    parser.add_argument("--exams", action="store_true", help="打印考试安排")
    parser.add_argument("--grades", action="store_true", help="尝试查询成绩（需验证码）")
    parser.add_argument("--week", type=int, help="配合 --courses 指定周次")
    parser.add_argument("--xnm", help="配合 --exams/--grades 指定学年，如 2026")
    parser.add_argument("--xqm", help="配合 --exams/--grades 指定学期代码，如 3")
    parser.add_argument("--json", action="store_true", help="配合查询命令输出 JSON")
    return parser


def setup_logging(config: Config) -> None:
    config.data_dir.mkdir(parents=True, exist_ok=True)
    formatter = logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
    log.setLevel(logging.INFO)
    log.handlers.clear()

    file_handler = logging.handlers.RotatingFileHandler(
        config.log_path, maxBytes=1_000_000, backupCount=3, encoding="utf-8"
    )
    file_handler.setFormatter(formatter)
    log.addHandler(file_handler)

    stream_handler = logging.StreamHandler()
    stream_handler.setFormatter(formatter)
    log.addHandler(stream_handler)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    config = Config.load(args.config)
    if args.no_details:
        config.fetch_details = False
    setup_logging(config)

    if args.test_notify:
        sample = [Item(
            source="whu_notice",
            external_id="test",
            title="测试通知：智慧珞珈公告抓取已配置",
            url="https://www.whu.edu.cn/tzgg.htm",
        )]
        delivered = notify(
            sample,
            desktop=config.desktop_notify,
            ntfy_url=config.ntfy_url,
            logger=log,
        )
        print("通知已发送" if delivered else "通知发送失败：没有可用渠道或渠道报错")
        return 0 if delivered else 1

    if args.set_cookie:
        target = config.session_path_for(args.session)
        try:
            session.save_cookie(target, sys.stdin.read())
        except (OSError, ValueError) as exc:
            log.error("保存 Cookie 失败: %s", exc)
            return 1
        print(f"已保存到 {target}（权限 0600）")
        return 0

    cookies = {
        "zhlj": session.load_cookie(config.session_path_for("zhlj")),
        "jwgl": session.load_cookie(config.session_path_for("jwgl")),
    }
    client = HttpClient(
        interval=config.interval_seconds,
        jitter=config.jitter_seconds,
        timeout=config.timeout_seconds,
        logger=log,
    )

    if args.check_session:
        return _check_session(client, cookies["zhlj"])
    if args.courses:
        return _show_courses(client, cookies["zhlj"], args.week, args.json)
    if args.exams:
        return _show_exams(client, cookies["jwgl"], args.xnm, args.xqm, args.json)
    if args.grades:
        return _show_grades(client, cookies["jwgl"], args.xnm, args.xqm, args.json)

    store = Store(config.db_path)
    try:
        if args.status:
            return _print_status(store)

        selected = args.source or config.sources
        unknown = [name for name in selected if name not in SOURCES]
        if unknown:
            log.error("未知来源: %s", ", ".join(unknown))
            return 1

        max_pages = args.max_pages or config.max_pages
        max_pages = max(1, max_pages)
        if args.backfill and not args.max_pages:
            max_pages = max(max_pages, 20)

        results: list[SourceResult] = []
        blocked = False
        for name in selected:
            source = SOURCES[name]
            session_name = getattr(source, "session_name", "zhlj")
            if getattr(source, "requires_auth", False) and not cookies.get(session_name):
                log.info("%s: 未配置 %s 会话，跳过（用 --set-cookie --session %s）",
                         source.label, session_name, session_name)
                continue
            if blocked:
                results.append(SourceResult(source=name, error="skipped after block"))
                continue
            try:
                result = _fetch_source(source, client, store, config,
                                       max_pages=max_pages, dry_run=args.dry_run,
                                       cookies=cookies)
            except BlockedError as exc:
                log.error("%s: 连接被连续断开，停止本轮抓取 (%s)", name, exc)
                result = SourceResult(source=name, error=f"blocked: {exc}")
                blocked = True
            except FetchError as exc:
                log.error("%s: %s", name, exc)
                result = SourceResult(source=name, error=str(exc))
            results.append(result)
            if not args.dry_run:
                store.record_run(result)

        new_items = [item for result in results for item in result.new_items]
        if args.no_notify or args.backfill:
            if new_items and not args.dry_run:
                store.mark_notified(new_items)
        elif not args.dry_run:
            pending = store.unnotified(limit=50)
            if pending:
                delivered = notify(
                    pending,
                    desktop=config.desktop_notify,
                    ntfy_url=config.ntfy_url,
                    logger=log,
                )
                if delivered:
                    store.mark_notified(pending)
                else:
                    log.warning("没有可用的通知渠道，%d 条新公告留待下次通知", len(pending))

        _summarize(results)
        if blocked:
            return 2
        return 0 if any(result.ok for result in results) else 1
    finally:
        store.close()


# ----------------------------------------------------------------------
def _fetch_source(source, client: HttpClient, store: Store, config: Config,
                  *, max_pages: int, dry_run: bool,
                  cookies: dict[str, str | None] | None = None) -> SourceResult:
    cookies = cookies or {}
    zhlj_cookie = cookies.get("zhlj")
    jwgl_cookie = cookies.get("jwgl")
    first_run = not dry_run and store.is_first_run(source.name)
    result = SourceResult(source=source.name)

    if isinstance(source, ZHLJMessageSource):
        if not zhlj_cookie:
            raise FetchError("未配置门户会话（--set-cookie）")
        text = source.fetch(client, zhlj_cookie)
        items = source.parse(text, source.start_url,
                             source_filter=config.zhlj_message_filter or None)
        result.pages = 1
        result.fetched = len(items)
        result.new_items = _store_items(items, store, dry_run)
        log.info("%s: 获取 %d 条，新增 %d 条", source.label, len(items),
                 len(result.new_items))
    elif isinstance(source, ZHLJNoticeSource):
        if not zhlj_cookie:
            raise FetchError("未配置门户会话（--set-cookie）")
        text = source.fetch(client, zhlj_cookie, count=config.zhlj_notice_count)
        items = source.parse(text, source.start_url, owner_ids=config.zhlj_owner_ids)
        result.pages = 1
        result.fetched = len(items)
        result.new_items = _store_items(items, store, dry_run)
        log.info("%s: 获取 %d 条，新增 %d 条", source.label, len(items),
                 len(result.new_items))
    elif isinstance(source, JWGLExamSource):
        if not jwgl_cookie:
            raise FetchError("未配置教务系统会话（--set-cookie --session jwgl）")
        exams, term = source.fetch(client, jwgl_cookie)
        items = source.parse(exams, term)
        result.pages = 1
        result.fetched = len(exams)
        result.new_items = _store_items(items, store, dry_run)
        log.info("%s: 获取 %d 条，新增 %d 条", source.label, len(exams),
                 len(result.new_items))
    elif isinstance(source, JsonSource):
        text = client.get(source.start_url)
        items = source.parse(text, source.start_url)
        result.pages = 1
        result.fetched = len(items)
        result.new_items = _store_items(items, store, dry_run)
        log.info("%s: 获取 %d 条，新增 %d 条", source.label, len(items),
                 len(result.new_items))
    elif isinstance(source, ListSource):
        url: str | None = source.start_url
        page = 0
        while url and page < max_pages:
            html = client.get(url, referer=source.start_url)
            items = source.parse(html, url)
            page += 1
            result.pages = page
            result.fetched += len(items)
            fresh = _store_items(items, store, dry_run)
            result.new_items.extend(fresh)
            log.info("%s: 第 %d 页 %d 条，新增 %d 条", source.label, page,
                     len(items), len(fresh))
            if not dry_run and not first_run and not fresh:
                break
            if page >= max_pages:
                break
            url = source.next_page(html, url)
    else:
        raise FetchError(f"unsupported source type: {type(source)!r}")

    if dry_run:
        return result

    store.mark_initialized(source.name)

    if first_run and result.new_items and not config.first_run_notify:
        store.mark_notified(result.new_items)
        log.info("%s: 首次运行，已记录 %d 条基线，不发送通知",
                 source.label, len(result.new_items))
        result.new_items = []

    if config.fetch_details and result.new_items:
        _fetch_details(source, result.new_items, client, store, config)

    return result


def _store_items(items: list, store: Store, dry_run: bool) -> list:
    if dry_run:
        for item in items:
            print(f"{item.published_at or '          '}  {item.title}  {item.url}")
        return []
    return store.insert_items(items)


def _fetch_details(source, items: list, client: HttpClient, store: Store,
                   config: Config) -> None:
    limit = config.max_details_per_run
    for item in items[:limit]:
        try:
            html = client.get(item.url, referer=source.start_url)
        except BlockedError as exc:
            # The list pages already succeeded; keep the new items rather
            # than losing them just because details were rate-limited.
            log.warning("详情抓取被限流，跳过本来源剩余详情: %s", exc)
            return
        except FetchError as exc:
            log.warning("详情抓取失败 %s: %s", item.url, exc)
            continue
        content, attachments = extract_detail(html, item.url)
        if not content and not attachments:
            continue
        content = content[:20000]
        store.update_content(item, content, attachments)
        item_with_detail = replace(item, content=content, attachments=attachments)
        items[items.index(item)] = item_with_detail
        log.info("详情: %s (%d 字, %d 个附件)", item.title, len(content), len(attachments))


def _check_session(client: HttpClient, cookie: str | None) -> int:
    if not cookie:
        print("未配置登录会话。先在已登录的页面里复制 Cookie，再运行：")
        print("  python3 -m zhlj --set-cookie")
        return 1
    try:
        term = course.current_term(course.fetch_terms(client, cookie))
    except (FetchError, BlockedError) as exc:
        print(f"会话无效或请求失败：{exc}")
        return 1
    week = course.current_week(term)
    print(f"会话有效：{term['yearTermName']}，当前第 {week} 周")
    return 0


def _show_courses(client: HttpClient, cookie: str | None,
                  week: int | None, as_json: bool) -> int:
    if not cookie:
        print("未配置登录会话。先运行 python3 -m zhlj --set-cookie")
        return 1
    try:
        term = course.current_term(course.fetch_terms(client, cookie))
        target = week or course.current_week(term)
        total = int(term.get("termWeekCount") or 0)
        if not 1 <= target <= total:
            print(f"周次超出范围：1-{total}")
            return 1
        data = course.fetch_curriculum(
            client, cookie, course.api_date_for_week(term, target)
        )
        courses = course.flatten(data)
    except (FetchError, BlockedError) as exc:
        print(f"课表获取失败：{exc}")
        return 1

    if as_json:
        print(course.to_json(term, target, courses))
    else:
        print(course.format_week(term, target, courses))
    return 0


def _show_exams(client: HttpClient, cookie: str | None, xnm: str | None,
                xqm: str | None, as_json: bool) -> int:
    if not cookie:
        print("未配置教务系统会话。先运行：")
        print("  python3 -m zhlj --set-cookie --session jwgl")
        return 1
    try:
        exams, term = jwgl.fetch_exams(client, cookie, xnm, xqm)
    except (FetchError, BlockedError) as exc:
        print(f"考试安排获取失败：{exc}")
        return 1
    if as_json:
        print(jwgl.exams_to_json(exams, term))
    else:
        print(jwgl.format_exams(exams, term))
    return 0


def _show_grades(client: HttpClient, cookie: str | None, xnm: str | None,
                 xqm: str | None, as_json: bool) -> int:
    if not cookie:
        print("未配置教务系统会话。先运行：")
        print("  python3 -m zhlj --set-cookie --session jwgl")
        return 1
    try:
        items, term = jwgl.fetch_grades(client, cookie, xnm, xqm)
    except jwgl.CaptchaRequired as exc:
        print(f"{exc}")
        print("请在浏览器打开「学生成绩查询」，手动过一次验证码后查看。")
        print("后台定时查询成绩不可用，这是教务系统的访问控制。")
        return 3
    except (FetchError, BlockedError) as exc:
        print(f"成绩查询失败：{exc}")
        return 1
    if as_json:
        print(json.dumps(items, ensure_ascii=False, indent=2))
    else:
        print(jwgl.format_grades(items, term))
    return 0


def _summarize(results: list[SourceResult]) -> None:
    total_new = sum(len(result.new_items) for result in results)
    log.info("本轮完成：%d 个来源，%d 条新公告", len(results), total_new)
    for result in results:
        if not result.ok:
            log.warning("  %s: 失败 — %s", result.source, result.error)


def _print_status(store: Store) -> int:
    rows = store.stats()
    if not rows:
        print("数据库为空。")
        return 0
    print(f"{'来源':<14}{'总数':>6}{'最新':>14}{'未通知':>8}")
    for row in rows:
        print(f"{row['source']:<14}{row['total']:>6}{row['latest'] or '-':>14}"
              f"{row['unnotified']:>8}")
    print("\n最近运行：")
    for row in store.recent_runs(8):
        status = "OK" if row["ok"] else "FAIL"
        print(f"{row['run_at']}  {row['source']:<12} {status:<5} "
              f"页{row['pages']} 取{row['fetched']} 新{row['new_items']}"
              + (f"  {row['error']}" if row["error"] else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
