package com.velmorath.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class NoticeLoaderTest {
    private val today = LocalDate.parse("2026-11-06")
    private fun item(id: Int, age: Long = 0) = CampusItem("通知$id", id = "$id", publishedAt = today.minusDays(age).toString())
    @Test fun paginatesByOffsetAndIncludesAllSourcesUntilOutsideThirtyDays() = runTest {
        val starts = mutableListOf<Int>()
        val feed = loadRecentNotices(today, pause = {}) { start, _ ->
            starts += start
            when (start) {
                1 -> (1..100).map { item(it, 1) }
                101 -> listOf(item(101, 29), item(102, 30))
                else -> listOf(item(103, 40))
            }
        }
        assertEquals(listOf(1, 101, 103), starts)
        assertEquals(101, feed.items.size)
        assertFalse(feed.partial)
    }
    @Test fun duplicatePageDoesNotLoopAndReportsPartialData() = runTest {
        var calls = 0
        val feed = loadRecentNotices(today, pause = {}) { _, _ -> calls++; listOf(item(1)) }
        assertEquals(2, calls)
        assertEquals(1, feed.items.size)
        assertTrue(feed.partial)
    }
    @Test fun laterFailureKeepsLoadedResultsAndReportsIncomplete() = runTest {
        val feed = loadRecentNotices(today, pause = {}) { start, _ ->
            if (start != 1) error("network") else listOf(item(1))
        }
        assertEquals(1, feed.items.size)
        assertTrue(feed.partial)
    }
}
