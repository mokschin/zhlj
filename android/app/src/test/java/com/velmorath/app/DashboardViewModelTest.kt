package com.velmorath.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    @Test fun missingLoginDoesNotMakeRequests() = runTest(dispatcher) {
        val model = DashboardViewModel(CampusSource { _, _ -> error("Must not request") })
        model.refresh("", "")
        advanceUntilIdle()
        assertTrue(model.states.value.values.all { it.needsLogin && !it.loading })
    }
    @Test fun assignmentListAppearsBeforeOriginalMetadataAndThenUpdates() = runTest(dispatcher) {
        val task = CampusItem("作业", id = "synthetic", source = "珞珈在线", publishedAt = "2026-11-01")
        val model = DashboardViewModel(CampusSource { section, _ ->
            CampusFeed(if (section == CampusSection.MESSAGES) listOf(task) else emptyList())
        }, assignmentSource = { item ->
            kotlinx.coroutines.delay(10_000)
            item.copy(startsAt = "2026-11-05 08:00", dueAt = "2026-11-10 23:59", submission = Submission.SUBMITTED, detailLoaded = true)
        })
        model.refresh("portal", "jwgl")
        advanceTimeBy(9001); runCurrent()
        val loading = model.states.value.getValue(CampusSection.MESSAGES)
        assertEquals("2026-11-01", loading.feed!!.items.single().publishedAt)
        assertTrue(loading.detailsLoading)
        advanceUntilIdle()
        val done = model.states.value.getValue(CampusSection.MESSAGES)
        assertEquals("2026-11-05 08:00", done.feed!!.items.single().startsAt)
        assertEquals(Submission.SUBMITTED, done.feed.items.single().submission)
        assertFalse(done.detailsLoading)
    }
    @Test fun selectingDatesCachesByMondayIncludingSunday() = runTest(dispatcher) {
        var calls = 0
        val model = DashboardViewModel(calendarSource = { _, date ->
            calls++; CampusFeed(emptyList(), weekStart = date.with(java.time.DayOfWeek.MONDAY).toString())
        })
        model.courseWeekView = true
        model.selectCourseDate(java.time.LocalDate.parse("2026-11-02"), "portal")
        advanceUntilIdle()
        model.selectCourseDate(java.time.LocalDate.parse("2026-11-08"), "portal")
        advanceUntilIdle()
        assertEquals(1, calls)
        model.selectCourseDate(java.time.LocalDate.parse("2026-11-09"), "portal")
        advanceUntilIdle()
        assertEquals(2, calls)
    }
    @Test fun monthViewLoadsOtherWeeksForCalendarMarkers() = runTest(dispatcher) {
        val dates = mutableListOf<java.time.LocalDate>()
        val model = DashboardViewModel(calendarSource = { _, date ->
            dates += date; CampusFeed(emptyList(), weekStart = date.with(java.time.DayOfWeek.MONDAY).toString())
        })
        model.selectCourseDate(java.time.LocalDate.parse("2026-11-02"), "portal")
        advanceUntilIdle()
        assertEquals(6, dates.size)
        assertNotNull(model.courseFeed(java.time.LocalDate.parse("2026-11-26"), null))
    }
    @Test fun duplicateRefreshIsIgnoredAndSourcesAreSpaced() = runTest(dispatcher) {
        val calls = mutableListOf<CampusSection>()
        val model = DashboardViewModel(CampusSource { section, _ ->
            calls += section; CampusFeed(emptyList())
        })
        model.refresh("portal", "jwgl")
        model.refresh("portal", "jwgl")
        runCurrent()
        assertEquals(listOf(CampusSection.NOTICES), calls)
        advanceTimeBy(2999)
        runCurrent()
        assertEquals(1, calls.size)
        advanceUntilIdle()
        assertEquals(CampusSection.entries.toList(), calls)
        assertTrue(model.states.value.values.none { it.loading })
    }
    @Test fun partialFailureKeepsPreviousResultsAndOtherSectionsStillUpdate() = runTest(dispatcher) {
        var failNotices = false
        val model = DashboardViewModel(CampusSource { section, _ ->
            if (failNotices && section == CampusSection.NOTICES) error("secret server response")
            CampusFeed(listOf(CampusItem(section.label)))
        })
        model.refresh("portal", "jwgl")
        advanceUntilIdle()
        val previous = model.states.value.getValue(CampusSection.NOTICES)
        failNotices = true
        model.refresh("portal", "jwgl")
        advanceUntilIdle()
        val failed = model.states.value.getValue(CampusSection.NOTICES)
        assertEquals(previous.feed, failed.feed)
        assertEquals(previous.updatedAt, failed.updatedAt)
        assertNotNull(failed.error)
        assertFalse(failed.error!!.contains("secret"))
        assertNull(model.states.value.getValue(CampusSection.EXAMS).error)
    }
    @Test fun missingPortalDoesNotBlockExams() = runTest(dispatcher) {
        val calls = mutableListOf<CampusSection>()
        val model = DashboardViewModel(CampusSource { section, _ ->
            calls += section; CampusFeed(emptyList())
        })
        model.refresh("", "jwgl")
        advanceUntilIdle()
        assertEquals(listOf(CampusSection.EXAMS), calls)
        assertTrue(model.states.value.getValue(CampusSection.NOTICES).needsLogin)
        assertNotNull(model.states.value.getValue(CampusSection.EXAMS).updatedAt)
    }

    @Test fun loginDuringRefreshQueuesTheNewSession() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<CampusSection, String>>()
        val model = DashboardViewModel(CampusSource { section, cookie ->
            calls += section to cookie
            CampusFeed(emptyList())
        })
        model.refresh("oldPortal", "")
        runCurrent()
        model.refresh("newPortal", "newJwgl", queueIfBusy = true)
        advanceUntilIdle()
        assertEquals(listOf("oldPortal", "oldPortal", "oldPortal",
            "newPortal", "newPortal", "newPortal", "newJwgl"), calls.map { it.second })
        assertTrue(model.states.value.values.none { it.needsLogin || it.loading })
    }

    @Test fun expiredSessionKeepsResultsAndRequestsLogin() = runTest(dispatcher) {
        var expired = false
        val model = DashboardViewModel(CampusSource { section, _ ->
            if (expired) throw SessionExpiredException()
            CampusFeed(listOf(CampusItem(section.label)))
        })
        model.refresh("portal", "jwgl")
        advanceUntilIdle()
        val before = model.states.value
        expired = true
        model.refresh("portal", "jwgl")
        advanceUntilIdle()
        CampusSection.entries.forEach { section ->
            val state = model.states.value.getValue(section)
            assertTrue(state.needsLogin)
            assertFalse(state.loading)
            assertEquals(before.getValue(section).feed, state.feed)
        }
    }
    @Test fun sectionRefreshLeavesOtherFeedsAndCalendarCacheIntact() = runTest(dispatcher) {
        val calls = mutableListOf<CampusSection>()
        var calendarCalls = 0
        val model = DashboardViewModel(CampusSource { section, _ -> calls += section; CampusFeed(emptyList()) },
            calendarSource = { _, date -> calendarCalls++; CampusFeed(emptyList(), weekStart = date.with(java.time.DayOfWeek.MONDAY).toString()) })
        model.courseWeekView = true
        val date = java.time.LocalDate.parse("2026-11-02")
        model.selectCourseDate(date, "portal")
        advanceUntilIdle()
        val courseState = model.states.value.getValue(CampusSection.COURSES)
        model.refresh("portal", "jwgl", sections = setOf(CampusSection.NOTICES))
        advanceUntilIdle()
        assertEquals(listOf(CampusSection.NOTICES), calls)
        assertEquals(courseState, model.states.value.getValue(CampusSection.COURSES))
        repeat(5) { model.selected = CampusSection.COURSES; model.selected = CampusSection.NOTICES }
        model.selectCourseDate(date.plusDays(3), "portal")
        advanceUntilIdle()
        assertEquals(1, calendarCalls)
        assertEquals(1, calls.size)
    }
    @Test fun differentSectionRefreshQueuesOnceWhileAnotherIsRunning() = runTest(dispatcher) {
        val calls = mutableListOf<CampusSection>()
        val model = DashboardViewModel(CampusSource { section, _ -> calls += section; kotlinx.coroutines.delay(1000); CampusFeed(emptyList()) })
        model.refresh("portal", "jwgl", sections = setOf(CampusSection.NOTICES))
        runCurrent()
        repeat(3) { model.refresh("portal", "jwgl", sections = setOf(CampusSection.EXAMS)) }
        advanceUntilIdle()
        assertEquals(listOf(CampusSection.NOTICES, CampusSection.EXAMS), calls)
    }

    @Test fun repeatedSelectionInAnInFlightWeekDoesNotRestartRequest() = runTest(dispatcher) {
        var calls = 0
        val model = DashboardViewModel(calendarSource = { _, date ->
            calls++; kotlinx.coroutines.delay(1000)
            CampusFeed(emptyList(), weekStart = date.with(java.time.DayOfWeek.MONDAY).toString())
        })
        model.courseWeekView = true
        val date = java.time.LocalDate.parse("2026-11-02")
        model.selectCourseDate(date, "portal")
        runCurrent()
        repeat(4) { model.selectCourseDate(date.plusDays(it.toLong()), "portal") }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(date.plusDays(3), model.courseDate)
    }

}
