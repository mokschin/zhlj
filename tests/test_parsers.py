from __future__ import annotations

import json
import unittest
from pathlib import Path

from zhlj.ids import article_key
from zhlj.sources.html_list import next_page_url, parse_list_page
from zhlj.sources.zhlj_api import ZHLJAnnouncementSource
from zhlj.sources.detail import extract_detail

FIXTURES = Path(__file__).parent / "fixtures"


def fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


class WHUListTests(unittest.TestCase):
    def setUp(self) -> None:
        self.items = parse_list_page(
            fixture("whu_tzgg_page1.html"),
            "https://www.whu.edu.cn/tzgg.htm",
            "whu_notice",
        )

    def test_extracts_table_rows(self) -> None:
        self.assertGreaterEqual(len(self.items), 10)

    def test_known_item(self) -> None:
        item = next(i for i in self.items if i.title == "珞珈讲坛第551讲预告")
        self.assertEqual(item.published_at, "2026-11-04")
        self.assertEqual(item.url, "https://www.whu.edu.cn/info/5231/277704.htm")
        self.assertEqual(item.external_id, "5231:277704")
        self.assertEqual(item.url_key, "article:5231:277704")

    def test_handles_jsp_url_form(self) -> None:
        item = next(
            i for i in self.items
            if i.title == "武汉大学2026年研究生国家奖学金推荐名单公示"
        )
        self.assertEqual(item.published_at, "2026-11-02")
        self.assertIn("wbnewsid=277514", item.url)
        self.assertEqual(item.url_key, "article:5231:277514")

    def test_next_page(self) -> None:
        html = fixture("whu_tzgg_page1.html")
        self.assertEqual(
            next_page_url(html, "https://www.whu.edu.cn/tzgg.htm"),
            "https://www.whu.edu.cn/tzgg/260.htm",
        )


class UCListTests(unittest.TestCase):
    def test_extracts_notices_and_dedupes(self) -> None:
        items = parse_list_page(
            fixture("uc_xstz_page1.html"),
            "https://uc.whu.edu.cn/tzgg/xstz.htm",
            "uc_notice",
        )
        item = next(
            i for i in items
            if i.title == "关于开展2026年武汉大学英诺大学生创新成果奖评选工作的通知"
        )
        self.assertEqual(item.published_at, "2026-11-02")
        self.assertEqual(item.url, "https://uc.whu.edu.cn/info/1517/128891.htm")
        urls = [i.url for i in items]
        self.assertEqual(len(urls), len(set(urls)))


class NICListTests(unittest.TestCase):
    def test_parses_and_combines_split_date(self) -> None:
        items = parse_list_page(
            fixture("nic_tzgg_page1.html"),
            "https://nic.whu.edu.cn/szwd/news/tzgg.htm",
            "nic_notice",
        )
        self.assertEqual(len(items), 5)
        item = next(i for i in items if "2025 年“数智武大”建设项目需求" in i.title)
        self.assertEqual(item.published_at, "2024-11-14")
        self.assertTrue(item.url.startswith("https://nic.whu.edu.cn/"))


class GSListTests(unittest.TestCase):
    def setUp(self) -> None:
        self.items = parse_list_page(
            fixture("gs_tzgg_page1.html"),
            "https://gs.whu.edu.cn/tzgg/qb.htm",
            "gs_notice",
        )

    def test_parses_content_jsp_and_info_links(self) -> None:
        self.assertEqual(len(self.items), 15)
        item = next(
            i for i in self.items
            if i.title == "关于申报2027年武汉大学与香港城市大学联合培养博士研究生项目的通知"
        )
        self.assertEqual(item.published_at, "2026-11-05")
        self.assertIn("content.jsp", item.url)
        self.assertEqual(item.url_key, "article:1063:109931")

    def test_next_page(self) -> None:
        html = fixture("gs_tzgg_page1.html")
        self.assertEqual(
            next_page_url(html, "https://gs.whu.edu.cn/tzgg/qb.htm"),
            "https://gs.whu.edu.cn/tzgg/qb/88.htm",
        )


class YouthLeagueTests(unittest.TestCase):
    def test_parses_day_month_date_and_heading_title(self) -> None:
        items = parse_list_page(
            fixture("future_gzdt_page1.html"),
            "https://future.whu.edu.cn/gzdt.htm",
            "future_news",
        )
        self.assertEqual(len(items), 5)
        first = items[0]
        self.assertEqual(first.published_at, "2026-11-02")
        self.assertEqual(first.title, "第十八届实践风采大赛决赛：实践砺青春 奋进新征程")
        self.assertEqual(
            next_page_url(fixture("future_gzdt_page1.html"),
                          "https://future.whu.edu.cn/gzdt.htm"),
            "https://future.whu.edu.cn/gzdt/134.htm",
        )


class DetailTests(unittest.TestCase):
    def test_extracts_body(self) -> None:
        text, attachments = extract_detail(
            fixture("whu_detail.html"),
            "https://www.whu.edu.cn/info/5231/277704.htm",
        )
        self.assertIn("魏悦广", text)
        self.assertIn("樱顶老图书馆", text)
        self.assertIsInstance(attachments, list)


class ZHLJApiTests(unittest.TestCase):
    def test_parses_announcements(self) -> None:
        items = ZHLJAnnouncementSource().parse(
            fixture("zhlj_announcements.json"),
            ZHLJAnnouncementSource.start_url,
        )
        self.assertEqual(len(items), 6)
        first = items[0]
        self.assertEqual(first.title, "珞珈讲坛第551讲预告")
        self.assertEqual(first.published_at, "2026-11-04")
        self.assertTrue(first.content)
        self.assertEqual(first.url_key, "article:5231:277704")

    def test_rejects_bad_payload(self) -> None:
        from zhlj.http import FetchError

        with self.assertRaises(FetchError):
            ZHLJAnnouncementSource().parse("not json", "https://example.test")


class ArticleKeyTests(unittest.TestCase):
    def test_info_and_jsp_forms_match(self) -> None:
        info = "https://www.whu.edu.cn/info/5231/277514.htm"
        jsp = ("https://www.whu.edu.cn/new2024/tzggnr.jsp?"
               "urltype=news.NewsContentUrl&wbtreeid=5231&wbnewsid=277514")
        self.assertEqual(article_key(info), article_key(jsp))

    def test_content_jsp_key(self) -> None:
        url = ("https://gs.whu.edu.cn/content.jsp?urltype=news.NewsContentUrl"
               "&wbtreeid=1063&wbnewsid=109931")
        self.assertEqual(article_key(url), "article:1063:109931")

    def test_same_article_on_uc_and_www(self) -> None:
        # Different tree ids are different articles even on sibling hosts.
        a = "https://uc.whu.edu.cn/info/1517/128891.htm"
        b = "https://www.whu.edu.cn/info/1517/128891.htm"
        self.assertEqual(article_key(a), article_key(b))


if __name__ == "__main__":
    unittest.main()
