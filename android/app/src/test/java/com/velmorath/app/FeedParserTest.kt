package com.velmorath.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FeedParserTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText())
    @Test fun noticesKeepAllOwnersTheirSourceContentAndOriginalLink() {
        val items = FeedParser.notices(fixture("zhlj_notice.json"))
        assertEquals(3, items.size)
        assertEquals(setOf("组织部", "团委", "学工部"), items.map { it.source }.toSet())
        assertTrue(items.all { it.url.startsWith("https://") && it.content.isNotBlank() && it.publishedAt.isNotBlank() })
    }
    @Test fun assignmentsKeepPublishTimeWithoutInventingADeadline() {
        val items = FeedParser.messages(fixture("zhlj_messages.json"))
        assertEquals(2, items.size)
        assertTrue(items.none { it.title.startsWith("作业") })
        assertTrue(items.all { it.url.contains("/share/") && it.publishedAt.isNotBlank() && it.dueAt.isBlank() })
        val withDeadline = fixture("zhlj_messages.json")
        withDeadline.getJSONObject("data").getJSONArray("dataList").getJSONObject(0).put("deadline", "2026-11-09 20:00")
        assertEquals("2026-11-09 20:00", FeedParser.messages(withDeadline).first().dueAt)
    }
    @Test fun coursesKeepStructuredPeriodLocationAndWeekInformation() {
        val items = FeedParser.courses(fixture("zhlj_curriculum.json"))
        assertEquals(3, items.size)
        assertEquals(listOf(1, 1, 4), items.map { it.course!!.day })
        assertEquals(13, items[1].course!!.to)
        assertEquals("1区101", items[0].course!!.room)
        assertEquals("第 6–12 周", items[0].course!!.weeks)
    }
    @Test fun noticeTimeSourceAndOrderFiltersCompose() {
        val items = FeedParser.notices(fixture("zhlj_notice.json"))
        val filter = NoticeFilter(LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-05"))
        assertEquals(listOf("团委", "学工部"), filter.apply(items).map { it.source })
        assertEquals(listOf("学工部", "团委"), filter.copy(newestFirst = false).apply(items).map { it.source })
        assertEquals(listOf("团委"), filter.copy(sourceIds = setOf("1855807458")).apply(items).map { it.source })
    }
    @Test fun linksAreResolvedAndExecutableSchemesAreRejected() {
        assertEquals("https://zhlj.whu.edu.cn/info/1", FeedParser.webUrl("/info/1"))
        assertEquals("", FeedParser.webUrl("javascript:alert(1)"))
        assertEquals("", FeedParser.webUrl(""))
    }
}
