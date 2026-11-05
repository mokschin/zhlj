from __future__ import annotations

import unittest

from zhlj.models import Item
from zhlj.notify import format_message


class NotifyFormatTests(unittest.TestCase):
    def test_single_item(self) -> None:
        title, body = format_message([
            Item(source="uc_notice", external_id="1517:1", title="退课通知",
                 url="https://uc.whu.edu.cn/info/1517/1.htm", published_at="2026-10-26")
        ])
        self.assertIn("1 条", title)
        self.assertIn("【本科院】退课通知（2026-10-26）", body)

    def test_truncates_large_batch(self) -> None:
        items = [
            Item(source="whu_notice", external_id=str(i), title=f"公告 {i}",
                 url=f"https://www.whu.edu.cn/info/5231/{i}.htm")
            for i in range(12)
        ]
        title, body = format_message(items)
        self.assertIn("12 条", title)
        self.assertIn("另有 4 条", body)
        self.assertLessEqual(len(body), 900)


if __name__ == "__main__":
    unittest.main()
