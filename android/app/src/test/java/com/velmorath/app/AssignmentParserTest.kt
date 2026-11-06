package com.velmorath.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssignmentParserTest {
    @Test fun dynamicNoticeProvidesStartEndAndActualWorkLinkButNotSubmission() {
        val json = JSONObject(javaClass.classLoader!!.getResourceAsStream("chaoxing_assignment_notice.json")!!.bufferedReader().readText())
        val item = AssignmentParser.fromNotice(CampusItem("示例作业", publishedAt = "2026-11-01"), json.getJSONObject("data"))
        assertEquals("2026-11-05 08:00", item.startsAt)
        assertEquals("2026-11-10 23:59", item.dueAt)
        assertEquals("2026-11-01", item.publishedAt)
        assertTrue(item.workUrl.contains("/work/"))
        assertTrue(item.detailLoaded)
        assertEquals(Submission.UNKNOWN, item.submission)
    }
    @Test fun personalSubmissionEvidenceIsSeparateFromOpenAndClosedWindow() {
        val item = CampusItem("作业", startsAt = "2026-11-05 08:00", dueAt = "2026-11-10 23:59")
        val submitted = AssignmentParser.fromHtml(item, "<div class='work-status'>已提交，待批阅</div>", true)
        assertEquals(Submission.SUBMITTED, submitted.submission)
        assertEquals("已结束", AssignmentParser.availability(item, LocalDateTime.parse("2026-11-11T00:00")))
        assertEquals(Submission.UNKNOWN, item.submission)
        assertEquals(Submission.NOT_SUBMITTED, AssignmentParser.fromHtml(item, "<p>提交状态：未提交</p>", true).submission)
        assertEquals(Submission.COMPLETED, AssignmentParser.fromHtml(item, "<p>完成状态：已完成</p>", true).submission)
    }
    @Test fun generalNoticeOrReadFlagsNeverImplyCompletion() {
        val item = CampusItem("作业")
        assertEquals(Submission.UNKNOWN, AssignmentParser.fromHtml(item, "<div class='work-status'>已完成</div>", false).submission)
        assertEquals(Submission.UNKNOWN, AssignmentParser.fromHtml(item, "<p>已读</p><p>作业已结束</p>", true).submission)
        assertEquals(Submission.UNKNOWN, AssignmentParser.fromHtml(item, "<p>请未完成作业的同学按时提交</p>", true).submission)
    }
    @Test fun recognizesHtmlLabelVariantsAndRejectsUnrelatedWorkLinks() {
        val item = AssignmentParser.fromHtml(CampusItem("作业"), "<div>开放时间：2026年11月5日 08:00</div><div>截止时间：2026/11/10 23:59</div>", false)
        assertNotNull(AssignmentParser.timestamp(item.startsAt))
        assertNotNull(AssignmentParser.timestamp(item.dueAt))
        assertFalse(AssignmentParser.isWorkUrl("https://chaoxing.com.evil.example/work/view"))
        assertFalse(AssignmentParser.isWorkUrl("javascript:work()"))
    }
}
