package com.velmorath.app

import org.junit.Test
import org.junit.Assert.*
import java.time.*

class UpcomingEventTest {
    private val date = LocalDate.parse("2026-11-06")
    private fun course(name: String, from: Int, to: Int) = CampusItem(name, course = CourseSlot(5, from, to, "教室", "教师"))
    @Test fun usesProvidedPeriodsAndIncludesOngoingCourse() {
        val feed = CampusFeed(listOf(course("第一门", 1, 2), course("第二门", 3, 4), course("晚课", 11, 13)), weekStart = "2026-11-02")
        val all = UpcomingEvent.all(listOf(feed), null, emptyList())
        assertEquals("08:00–09:35", all[0].time)
        assertEquals("18:30–20:55", all[2].time)
        val active = UpcomingEvent.select(all, date.atTime(8, 30))
        assertEquals(listOf("第一门"), active.map { it.item.title })
        assertEquals(date.atTime(9, 35), active.single().end)
        assertEquals(listOf("第二门"), UpcomingEvent.select(all, date.atTime(9, 35)).map { it.item.title })
    }
    @Test fun separateWindowsAndOneCoursePlusOneCustomWithMultipleExams() {
        val now = date.atTime(8, 0)
        val feeds = listOf(CampusFeed(listOf(course("早课", 3, 4), course("下午课", 6, 7), course("晚课", 11, 13)), weekStart = "2026-11-02"))
        val exams = CampusFeed(listOf(
            CampusItem("考试1", "2026-11-06 · 16:00-18:00"),
            CampusItem("考试2", "2026-11-08 · 08:00-10:00"),
            CampusItem("太远考试", "2026-11-08 · 08:01-10:00")))
        val personal = listOf(PersonalEvent(name = "会议", tag = "工作", start = now.plusHours(2), end = now.plusHours(3), place = "", color = 1),
            PersonalEvent(name = "另一会议", tag = "", start = now.plusHours(3), end = now.plusHours(4), place = "", color = 2))
        val selected = UpcomingEvent.select(UpcomingEvent.all(feeds, exams, personal), now)
        assertEquals(setOf("早课", "会议", "考试1", "考试2"), selected.map { it.item.title }.toSet())
        assertEquals(1, selected.count { !it.exam && !it.custom })
        assertEquals(1, selected.count { it.custom })
        assertEquals("10:00–11:00", selected.first { it.custom }.time)
        assertEquals(2, selected.count { it.exam })
    }
    @Test fun sixHourBoundaryAndEndedEventsAreExcluded() {
        val now = date.atTime(8, 5)
        val all = UpcomingEvent.all(listOf(CampusFeed(listOf(course("下午课", 6, 7)), weekStart = "2026-11-02")), null, emptyList())
        assertEquals(1, UpcomingEvent.select(all, now).size)
        assertTrue(UpcomingEvent.select(all, now.minusSeconds(1)).isEmpty())
        assertTrue(UpcomingEvent.select(all, date.atTime(15, 40)).isEmpty())
    }
    @Test fun allThirteenPeriodsAndInvalidIndices() {
        assertEquals("08:00", CourseTimes.starts.first()); assertEquals("20:55", CourseTimes.ends.last())
        assertEquals("14:05–18:15", CourseTimes.label(CourseSlot(1, 6, 10, "", "")))
        assertNull(CourseTimes.range(date, CourseSlot(1, 0, 14, "", "")))
    }
}
