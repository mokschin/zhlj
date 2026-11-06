package com.velmorath.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.YearMonth

fun interface CampusSource {
    suspend fun load(section: CampusSection, cookie: String): CampusFeed
}
class DashboardViewModel(
    private val source: CampusSource = CampusSource { section, cookie ->
        when (section) {
            CampusSection.NOTICES -> ZhljApi.notices(cookie)
            CampusSection.MESSAGES -> ZhljApi.messages(cookie)
            CampusSection.COURSES -> ZhljApi.courses(cookie)
            CampusSection.EXAMS -> ZhljApi.exams(cookie)
        }
    },
    private val assignmentSource: suspend (CampusItem) -> CampusItem = ZhljApi::assignmentDetails,
    private val calendarSource: suspend (String, LocalDate) -> CampusFeed = { cookie, date -> ZhljApi.courses(cookie, date) },
) : ViewModel() {
    private val mutableStates = MutableStateFlow(CampusSection.entries.associateWith { FeedState() })
    val states = mutableStates.asStateFlow()
    var initialRequested = false
    var selected = CampusSection.NOTICES
    var settingsSelected = false
    var settingsPage = SettingsPage.ROOT
    var noticeFilter = NoticeFilter()
    var favoritesOnly = false
    var courseDay = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).dayOfWeek.value
    var courseWeekView = false
    var upcomingSelected = false
    var enabledSections = CampusSection.entries.toSet()
    var courseDate = FeedParser.today()
    var courseMonth = YearMonth.from(courseDate)
    var assignmentFilter = "全部"
    var personalEvents: List<PersonalEvent> = emptyList()
    var localRevision = 0
    var onDataChanged: (() -> Unit)? = null
    fun allCourseFeeds(): List<CampusFeed> = (weeks.values + listOfNotNull(states.value.getValue(CampusSection.COURSES).feed)).distinctBy { it.weekStart }
    fun restoreSchedule(feeds: List<CampusFeed>, exams: CampusFeed, personal: List<PersonalEvent>) {
        feeds.filter { it.weekStart.isNotBlank() }.forEach { weeks[it.weekStart] = it }
        personalEvents = personal
        val current = courseFeed(FeedParser.today(), null)
        if (current != null) update(CampusSection.COURSES, FeedState(current))
        if (exams.items.isNotEmpty()) update(CampusSection.EXAMS, FeedState(exams))
    }
    suspend fun monthFeeds(cookie: String, month: YearMonth): List<CampusFeed> {
        val result = mutableListOf<CampusFeed>()
        var monday = month.atDay(1).with(java.time.DayOfWeek.MONDAY)
        while (!monday.isAfter(month.atEndOfMonth())) {
            val key = monday.toString()
            val feed = weeks[key] ?: sourceLock.withLock { delay(3000); calendarSource(cookie, monday.plusDays(2)) }.also { weeks[key] = it }
            result += feed; monday = monday.plusWeeks(1)
        }
        onDataChanged?.invoke()
        return result
    }
    private val weeks = mutableMapOf<String, CampusFeed>()
    private val sourceLock = Mutex()
    private var calendarJob: Job? = null
    private var requestedWeek: String? = null
    private var monthJob: Job? = null
    private var requestedMonth: YearMonth? = null
    private fun loadCourseMonth(cookie: String) {
        if (courseWeekView || cookie.isBlank() || requestedMonth == courseMonth && monthJob?.isActive == true) return
        monthJob?.cancel()
        val month = courseMonth; requestedMonth = month
        monthJob = viewModelScope.launch {
            var monday = month.atDay(1).with(java.time.DayOfWeek.MONDAY)
            while (!monday.isAfter(month.atEndOfMonth())) {
                val key = monday.toString()
                if (!weeks.containsKey(key)) {
                    try {
                        val feed = sourceLock.withLock { weeks[key] ?: run { delay(3000); calendarSource(cookie, monday.plusDays(2)) }.also { weeks[key] = it } }
                        weeks[key] = feed
                        val state = states.value.getValue(CampusSection.COURSES)
                        update(CampusSection.COURSES, state.copy(calendarRevision = state.calendarRevision + 1))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* An unknown date stays unknown until selected or refreshed. */ }
                }
                monday = monday.plusWeeks(1)
            }
        }
    }
    fun courseFeed(date: LocalDate, fallback: CampusFeed?): CampusFeed? {
        val key = date.with(java.time.DayOfWeek.MONDAY).toString()
        return weeks[key] ?: fallback?.takeIf { it.weekStart == key }
    }
    fun selectCourseDate(date: LocalDate, cookie: String) {
        courseDate = date; courseMonth = YearMonth.from(date); courseDay = date.dayOfWeek.value
        courseFeed(date, states.value.getValue(CampusSection.COURSES).feed)?.let {
            update(CampusSection.COURSES, FeedState(it, updatedAt = System.currentTimeMillis())); loadCourseMonth(cookie); return
        }
        val key = date.with(java.time.DayOfWeek.MONDAY).toString()
        if (requestedWeek == key && calendarJob?.isActive == true) return
        calendarJob?.cancel()
        requestedWeek = key
        if (cookie.isBlank()) { update(CampusSection.COURSES, FeedState(needsLogin = true)); return }
        update(CampusSection.COURSES, FeedState(loading = true))
        calendarJob = viewModelScope.launch {
            try {
                val feed = sourceLock.withLock { weeks[key] ?: calendarSource(cookie, date).also { weeks[key] = it } }
                weeks[date.with(java.time.DayOfWeek.MONDAY).toString()] = feed
                update(CampusSection.COURSES, FeedState(feed, updatedAt = System.currentTimeMillis()))
                loadCourseMonth(cookie)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SessionExpiredException) { update(CampusSection.COURSES, FeedState(needsLogin = true)) }
            catch (_: Exception) { update(CampusSection.COURSES, FeedState(error = "课表加载失败，请重试")) }
        }
    }
    fun assignmentPage(id: String, html: String) {
        val state = states.value.getValue(CampusSection.MESSAGES)
        val feed = state.feed ?: return
        update(CampusSection.MESSAGES, state.copy(feed = feed.copy(items = feed.items.map {
            if (it.id == id) AssignmentParser.fromHtml(it, html, personal = true) else it
        })))
    }
    private var refreshJob: Job? = null
    private var pendingSections: Triple<String, String, Set<CampusSection>>? = null
    private var pendingLoginRefresh: Pair<String, String>? = null
    fun refresh(portal: String, jwgl: String, queueIfBusy: Boolean = false, sections: Set<CampusSection> = enabledSections) {
        if (refreshJob?.isActive == true) {
            if (queueIfBusy) pendingLoginRefresh = portal to jwgl
            else {
                val idle = sections.filter { !states.value.getValue(it).loading && !states.value.getValue(it).detailsLoading }.toSet()
                if (idle.isNotEmpty()) pendingSections = Triple(portal, jwgl, pendingSections?.third.orEmpty() + idle)
            }
            return
        }
        if (CampusSection.COURSES in sections) {
            monthJob?.cancel(); calendarJob?.cancel()
        }
        if (queueIfBusy) weeks.clear()
        val previous = mutableStates.value
        mutableStates.value = CampusSection.entries.associateWith { section ->
            if (section !in sections) return@associateWith previous.getValue(section)
            val available = (if (section == CampusSection.EXAMS) jwgl else portal).isNotBlank()
            if (available) previous.getValue(section).copy(loading = true, error = null, needsLogin = false)
            else FeedState(needsLogin = true)
        }
        refreshJob = viewModelScope.launch {
            val requested = CampusSection.entries.filter { it in sections }
            requested.forEachIndexed { index, section ->
                val cookie = if (section == CampusSection.EXAMS) jwgl else portal
                val old = previous.getValue(section)
                if (cookie.isBlank()) {
                    update(section, FeedState(needsLogin = true))
                } else {
                    update(section, old.copy(loading = true, error = null, needsLogin = false))
                    try {
                        val feed = sourceLock.withLock {
                            if (section == CampusSection.COURSES && courseDate != FeedParser.today()) calendarSource(cookie, courseDate)
                            else source.load(section, cookie)
                        }
                        if (section == CampusSection.COURSES && feed.weekStart.isNotBlank()) {
                            weeks[feed.weekStart] = feed
                            loadCourseMonth(cookie)
                        }
                        update(section, FeedState(feed = feed, updatedAt = System.currentTimeMillis()))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: SessionExpiredException) {
                        update(section, old.copy(loading = false, needsLogin = true,
                            error = "登录已过期，请到设置重新登录"))
                    } catch (_: Exception) {
                        update(section, old.copy(loading = false, needsLogin = false,
                            error = "加载失败，请检查网络或重新登录"))
                    }
                    if (index < requested.lastIndex) delay(3000)
                }
            }
            val messageState = states.value.getValue(CampusSection.MESSAGES)
            if (CampusSection.MESSAGES in sections && messageState.feed != null && !messageState.needsLogin && messageState.error == null) {
                val items = messageState.feed.items.toMutableList()
                update(CampusSection.MESSAGES, messageState.copy(detailsLoading = items.any { it.source == "珞珈在线" }))
                for (i in items.indices) {
                    if (items[i].source != "珞珈在线") continue
                    delay(3000)
                    try { items[i] = sourceLock.withLock { assignmentSource(items[i]) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Leave unknown values explicit, keep the rest of the feed. */ }
                    val current = states.value.getValue(CampusSection.MESSAGES)
                    val known = current.feed?.items.orEmpty().associateBy { it.id }
                    val merged = items.map { item ->
                        if (item.submission == Submission.UNKNOWN && known[item.id]?.submission != Submission.UNKNOWN)
                            item.copy(submission = known[item.id]?.submission ?: Submission.UNKNOWN) else item
                    }
                    update(CampusSection.MESSAGES, current.copy(feed = current.feed?.copy(items = merged)))
                }
                val current = states.value.getValue(CampusSection.MESSAGES)
                update(CampusSection.MESSAGES, current.copy(detailsLoading = false, updatedAt = System.currentTimeMillis()))
            }
            pendingLoginRefresh?.let { (nextPortal, nextJwgl) ->
                pendingLoginRefresh = null
                delay(3000)
                refreshJob = null
                pendingSections = null
                refresh(nextPortal, nextJwgl, queueIfBusy = true)
            } ?: pendingSections?.let { (nextPortal, nextJwgl, nextSections) ->
                pendingSections = null
                delay(3000)
                refreshJob = null
                refresh(nextPortal, nextJwgl, sections = nextSections)
            }
        }
    }
    private fun update(section: CampusSection, state: FeedState) {
        mutableStates.value = mutableStates.value + (section to state)
        onDataChanged?.invoke()
    }
}
