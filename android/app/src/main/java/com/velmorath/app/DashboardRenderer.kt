package com.velmorath.app

import android.app.DatePickerDialog
import android.graphics.Typeface
import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Each section has its own information hierarchy; the frame only owns navigation and status. */
class DashboardRenderer(private val activity: MainActivity, private val model: DashboardViewModel,
    private val onAccount: (Boolean) -> Unit, private val onAppearance: () -> Unit,
    private val onItem: (CampusItem) -> Unit, private val onRefresh: () -> Unit,
    private val onCourseDate: (LocalDate) -> Unit = {}) {
    private val content get() = activity.findViewById<LinearLayout>(R.id.feedContent)
    private val controls get() = activity.findViewById<LinearLayout>(R.id.feedControls)
    private var lastRender: List<Any?>? = null
    private val ui = UiKit(activity)
    fun render(states: Map<CampusSection, FeedState>) {
        val settings = model.settingsSelected
        val section = model.selected
        val state = states.getValue(section)
        val busy = if (settings) states.values.any { it.loading || it.detailsLoading } else if (model.upcomingSelected) listOf(CampusSection.COURSES, CampusSection.EXAMS).any { states.getValue(it).loading } else state.loading
        val key = listOf(model.favoritesOnly, model.settingsPage, settings, model.upcomingSelected, section, if (settings || model.upcomingSelected) states else state, busy, model.noticeFilter,
            model.courseDate, model.courseMonth, model.courseWeekView, model.assignmentFilter, model.localRevision, if (model.upcomingSelected) System.currentTimeMillis() / 30000 else 0)
        if (key == lastRender) return
        lastRender = key
        content.removeAllViews(); controls.removeAllViews()
        text(R.id.pageTitle, if (settings) model.settingsPage.title else if (model.upcomingSelected) "接下来" else section.label)
        activity.findViewById<View>(R.id.settingsBack).apply {
            visibility = if (settings && model.settingsPage != SettingsPage.ROOT) View.VISIBLE else View.GONE
            setOnClickListener { activity.openSettingsPage(model.settingsPage.parent) }
        }
        visible(R.id.pageHeader, settings || model.upcomingSelected || section != CampusSection.COURSES)
        activity.findViewById<View>(R.id.refresh).apply { visibility = if (settings) View.GONE else View.VISIBLE; isEnabled = !busy && !state.detailsLoading }
        activity.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefresh).apply {
            isEnabled = !settings; isRefreshing = busy && !settings
        }
        activity.findViewById<android.widget.ImageButton>(R.id.secondaryAction).apply {
            visibility = if (!settings && (model.upcomingSelected || section == CampusSection.NOTICES)) View.VISIBLE else View.GONE
            setImageResource(if (model.upcomingSelected) R.drawable.ic_add else R.drawable.ic_settings)
            contentDescription = if (model.upcomingSelected) "新建日程" else "通知设置"
            setOnClickListener { if (model.upcomingSelected) activity.editSchedule() else activity.openMessageSettings() }
        }
        activity.findViewById<android.widget.ImageButton>(R.id.favoritesAction).apply {
            visibility = if (!settings && !model.upcomingSelected && section == CampusSection.NOTICES) View.VISIBLE else View.GONE
            setImageResource(if (model.favoritesOnly) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark)
            isSelected = model.favoritesOnly
            contentDescription = if (model.favoritesOnly) "显示全部通知" else "只看收藏"
            setOnClickListener { model.favoritesOnly = !model.favoritesOnly; redraw() }
        }
        visible(R.id.progress, false)
        visible(R.id.statusPanel, !settings && !model.upcomingSelected && (state.needsLogin || state.error != null || state.feed?.partial == true))
        text(R.id.summaryTitle, when {
            state.needsLogin -> "在设置中登录${if (section == CampusSection.EXAMS) "教务" else "智慧珞珈"}"
            state.error != null -> "更新失败"
            state.feed?.partial == true -> "部分内容未加载，刷新重试"
            else -> ""
        })
        text(R.id.summaryDetail, state.error.orEmpty())
        visible(R.id.summaryDetail, state.error != null)
        if (settings) { renderSettings(busy, states); return }
        if (model.upcomingSelected) { renderUpcoming(states); return }
        when (section) {
            CampusSection.NOTICES -> renderNotices(state)
            CampusSection.MESSAGES -> renderMessages(state)
            CampusSection.COURSES -> renderCourses(state)
            CampusSection.EXAMS -> renderExams(state)
        }
        if (state.needsLogin) content.addView(ui.primary("登录${if (section == CampusSection.EXAMS) "本科教务" else "智慧珞珈"}") { onAccount(section == CampusSection.EXAMS) })
        else if (state.error != null || state.feed?.partial == true) content.addView(ui.action("重新加载") { onRefresh() })
        if (content.childCount == 0 && !state.needsLogin) {
            val message = when {
                state.loading -> "正在加载"
                state.error != null -> "刷新重试"
                state.feed == null -> "下拉刷新"
                section == CampusSection.NOTICES -> "没有符合筛选的通知"
                section == CampusSection.MESSAGES -> "没有符合筛选的作业"
                else -> section.emptyText
            }
            content.addView(label(message, 16f, R.color.muted).apply { setPadding(0, dp(30), 0, dp(30)) })
            if (!state.loading && state.error == null) {
                if (section == CampusSection.NOTICES || section == CampusSection.MESSAGES) content.addView(ui.action("清除筛选") {
                    model.noticeFilter = NoticeFilter(LocalDate.MIN, LocalDate.MAX); Reminders.prefs(activity).edit().remove("filter_source").remove("filter_sources").putBoolean("newest", true).apply(); model.assignmentFilter = "全部"; redraw()
                })
                else content.addView(ui.action("刷新") { onRefresh() })
            }
        }
    }
    private fun renderNotices(state: FeedState) {
        val favorites = NoticeFavorites(activity)
        val saved = favorites.items().associateBy(NoticeFavorites::key)
        val feed = state.feed?.items.orEmpty()
        val candidates = if (model.favoritesOnly) (saved.values + feed.filter { NoticeFavorites.key(it) in saved }).associateBy(NoticeFavorites::key).values.toList() else feed
        // Favorites have their own scope; the source filter still controls the normal feed.
        val filter = if (model.favoritesOnly) model.noticeFilter.copy(from = LocalDate.MIN, until = LocalDate.MAX, sourceIds = null) else model.noticeFilter
        val items = filter.apply(candidates)
        if (model.favoritesOnly && items.isEmpty()) {
            content.addView(label("还没有收藏通知，向右滑动通知即可收藏", 15f, R.color.muted).apply { setPadding(0, dp(24), 0, dp(24)) })
        }
        items.forEach { item -> content.addView(NoticeCard(activity, item, NoticeFavorites.key(item) in saved, { onItem(item) }) {
            val added = favorites.toggle(item)
            android.widget.Toast.makeText(activity, if (added) "已收藏，再次右滑可取消" else "已取消收藏", android.widget.Toast.LENGTH_SHORT).show()
            model.localRevision++; redraw()
        }) }
    }
    private fun renderMessages(state: FeedState) {
        val items = state.feed?.items.orEmpty()
        val bar = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val options = listOf("全部", "未提交", "已提交", "已完成", "状态未知")
        bar.addView(ui.menu(model.assignmentFilter, options, options.indexOf(model.assignmentFilter)) { index ->
            model.assignmentFilter = options[index]; redraw()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ui.menu("更多", listOf("同步范围")) {
            val times = items.map { it.publishedAt }.filter { it.isNotBlank() }.sorted()
            val starts = items.map { it.startsAt }.filter { it.isNotBlank() }.sorted()
            val ends = items.map { it.dueAt }.filter { it.isNotBlank() }.sorted()
            val updated = state.updatedAt?.let { java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(it)) } ?: "尚未同步"
            MaterialAlertDialogBuilder(activity).setTitle("本次同步")
                .setMessage("范围：平台当前返回，非全部历史\n最早发布：${times.firstOrNull() ?: "未知"}\n最新发布：${times.lastOrNull() ?: "未知"}\n最早开始：${starts.firstOrNull() ?: "未知"}\n最晚结束：${ends.lastOrNull() ?: "未知"}\n作业与课程提醒：${items.size} 条\n原文已同步：${items.count { it.detailLoaded }} 条\n状态已确认：${items.count { it.submission != Submission.UNKNOWN }} 条\n更新时间：$updated")
                .setPositiveButton("关闭", null).show().findViewById<TextView>(android.R.id.message)?.textSize = 16f
        })
        controls.addView(bar)
        items.filter { model.assignmentFilter == "全部" || it.submission.label == model.assignmentFilter }.forEach { item ->
            fun short(value: String) = AssignmentParser.timestamp(value)?.format(DateTimeFormatter.ofPattern("M/d HH:mm")) ?: value
            val deadline = item.dueAt.takeIf { it.isNotBlank() }?.let { "截止  ${short(it)}" }
                ?: if (state.detailsLoading) "正在补全截止时间" else "截止时间未知"
            val availability = AssignmentParser.availability(item)
            val urgent = item.submission == Submission.NOT_SUBMITTED && availability == "进行中" &&
                AssignmentParser.timestamp(item.dueAt)?.let { java.time.Duration.between(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")), it).toHours() in 0..48 } == true
            content.addView(card(item, deadline, listOf(availability, item.submission.label).filter { it.isNotBlank() }.joinToString(" · "), statusColor = when {
                urgent -> R.color.warning
                item.submission == Submission.SUBMITTED || item.submission == Submission.COMPLETED -> R.color.success
                else -> R.color.muted
            }))
        }
    }
    private fun renderCourses(state: FeedState) {
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("${model.courseMonth.year} 年 ${model.courseMonth.monthValue} 月", 20f, R.color.ink).apply {
            setTypeface(typeface, Typeface.BOLD); maxLines = 1
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 14, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            setOnClickListener {
                DatePickerDialog(activity, { _, year, month, day -> onCourseDate(LocalDate.of(year, month + 1, day)); redraw() }, model.courseDate.year, model.courseDate.monthValue - 1, model.courseDate.dayOfMonth).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ui.icon(R.drawable.ic_add, "新建日程") { activity.editSchedule(date = model.courseDate) })
        header.addView(ui.action("今天", true) { onCourseDate(FeedParser.today()); redraw() })
        header.addView(ui.icon(if (model.courseWeekView) R.drawable.ic_course else R.drawable.ic_week, if (model.courseWeekView) "切换月历" else "切换周表") {
            model.courseWeekView = !model.courseWeekView; redraw()
        })
        controls.addView(header)
        val feed = model.courseFeed(model.courseDate, state.feed)
        if (model.courseWeekView) {
            val week = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            fun moveWeek(days: Long) { onCourseDate(model.courseDate.plusDays(days)); redraw() }
            week.addView(ui.icon(R.drawable.ic_chevron_left, "上一周") { moveWeek(-7) })
            val monday = model.courseDate.with(java.time.DayOfWeek.MONDAY)
            week.addView(label("${monday.monthValue}/${monday.dayOfMonth} – ${monday.plusDays(6).monthValue}/${monday.plusDays(6).dayOfMonth}", 14f, R.color.muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            week.addView(ui.icon(R.drawable.ic_chevron_right, "下一周") { moveWeek(7) })
            content.addView(week)
            if (feed != null) content.addView(TimetableView(activity, feed) { items ->
                if (items.size == 1) onItem(items.first()) else MaterialAlertDialogBuilder(activity)
                    .setTitle("课程冲突").setItems(items.map { it.title }.toTypedArray()) { _, index -> onItem(items[index]) }.show()
            }) else content.addView(label(if (state.loading) "正在加载" else "暂无课程数据", 14f, R.color.muted))
            return
        }
        content.addView(CourseCalendarView(activity, model.courseMonth, model.courseDate,
            events = { date -> model.courseFeed(date, state.feed)?.items.orEmpty().filter { it.course?.day == date.dayOfWeek.value } + model.personalEvents.filter { it.start < date.plusDays(1).atStartOfDay() && it.end > date.atStartOfDay() }.map { it.item() } },
            onDate = { date -> onCourseDate(date); redraw() },
            onMonth = { month -> onCourseDate(month.atDay(minOf(model.courseDate.dayOfMonth, month.lengthOfMonth()))); redraw() }))
        val lessons = feed?.items?.filter { it.course?.day == model.courseDate.dayOfWeek.value }
        content.addView(View(activity), LinearLayout.LayoutParams(1, dp(12)))
        val personal = model.personalEvents.filter { it.start < model.courseDate.plusDays(1).atStartOfDay() && it.end > model.courseDate.atStartOfDay() }
        if (lessons == null) content.addView(label(if (state.loading) "正在加载课程" else "暂无课程数据", 14f, R.color.muted))
        else if (lessons.isEmpty() && personal.isEmpty()) content.addView(label(if (feed.caption == "非当前学期") "非当前学期" else "当天没有日程", 14f, R.color.muted))
        (lessons.orEmpty() + personal.map { it.item() }).sortedBy { item ->
            item.personal?.start ?: CourseTimes.range(model.courseDate, item.course!!)?.first ?: model.courseDate.atTime(java.time.LocalTime.MAX)
        }.forEach { item -> content.addView(item.personal?.let { personalCard(it) } ?: courseCard(item)) }
    }
    private fun courseCard(item: CampusItem): View {
        val slot = item.course!!
        return agendaCard(item, CourseTimes.label(slot), slot.room, CoursePalette.colors(activity, item.title).second)
    }
    private fun agendaCard(item: CampusItem, time: String, place: String, marker: Int, tag: String = ""): View {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(UiStyle.CARD_PADDING), dp(UiStyle.CARD_VERTICAL), dp(UiStyle.CARD_PADDING), dp(UiStyle.CARD_VERTICAL))
            val title = label(item.title, UiStyle.CARD_TITLE, R.color.ink).apply {
                setTypeface(typeface, Typeface.BOLD)
                val dot = ui.shape(marker, 8).apply { setBounds(0, 0, dp(12), dp(12)) }
                setCompoundDrawablesRelative(dot, null, null, null); compoundDrawablePadding = dp(9)
            }
            addView(title)
            fun metadata(value: String, icon: Int) = label(value, 14f, R.color.muted).apply {
                val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, icon)!!.mutate()
                drawable.setTint(color(R.color.muted)); drawable.setBounds(0, 0, dp(15), dp(15))
                setCompoundDrawablesRelative(drawable, null, null, null); compoundDrawablePadding = dp(7)
            }
            addView(metadata(time, R.drawable.ic_clock).apply { setPadding(0, dp(5), 0, 0) })
            addView(metadata(place.ifBlank { "地点待定" }, R.drawable.ic_location))
            if (tag.isNotBlank()) addView(label(tag, 12f, R.color.muted).apply { setPadding(dp(22), dp(3), 0, 0) })
            isClickable = true; isFocusable = true; setOnClickListener { onItem(item) }
        }
        return MaterialCardView(activity).apply {
            radius = dp(UiStyle.CARD_RADIUS).toFloat(); strokeWidth = 0; cardElevation = 0f; setCardBackgroundColor(color(R.color.card))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(UiStyle.CARD_GAP) }; addView(body)
            isClickable = true; isFocusable = true; setOnClickListener { onItem(item) }
        }
    }
    private fun personalCard(event: PersonalEvent): View {
        val format = DateTimeFormatter.ofPattern(if (event.start.toLocalDate() == event.end.toLocalDate()) "HH:mm" else "M/d HH:mm")
        return agendaCard(event.item(), "${event.start.format(format)}–${event.end.format(format)}", event.place, event.color, event.tag)
    }
    private fun renderUpcoming(states: Map<CampusSection, FeedState>) {
        val events = UpcomingEvent.collect(model, states)
        heading("即将开始")
        if (events.isEmpty()) content.addView(label("暂无接下来的安排", 15f, R.color.muted).apply { setPadding(0, dp(12), 0, dp(12)) })
        events.forEach { event -> content.addView(UpcomingCard(activity, event) { onItem(event.item) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(UiStyle.CARD_GAP) }) }
        val courseState = states.getValue(CampusSection.COURSES)
        val examState = states.getValue(CampusSection.EXAMS)
        if (courseState.needsLogin) content.addView(ui.action("登录智慧珞珈，查看课程") { onAccount(false) })
        if (examState.needsLogin) content.addView(ui.action("登录本科教务，查看考试") { onAccount(true) })
        if (courseState.error != null || examState.error != null) content.addView(ui.action("部分安排加载失败，重试") { onRefresh() })
    }
    private fun renderExams(state: FeedState) {
        val today = FeedParser.today().toString()
        state.feed?.items?.sortedWith(compareBy<CampusItem> { it.subtitle.take(10) < today }.thenBy { it.subtitle })?.forEach { item ->
            val date = FeedParser.date(item.subtitle.take(10))
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(UiStyle.CARD_PADDING), dp(UiStyle.CARD_VERTICAL), dp(UiStyle.CARD_PADDING), dp(UiStyle.CARD_VERTICAL)) }
            row.addView(label(date?.let { "${it.monthValue}月\n${it.dayOfMonth}日" } ?: "待定", 19f, R.color.accent).apply { gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(dp(64), -2))
            row.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                addView(label(item.title, UiStyle.CARD_TITLE, R.color.ink).apply { setTypeface(typeface, Typeface.BOLD) })
                addView(label(if (date != null) "${date.year}年  ${item.subtitle.drop(10).trim()}" else item.subtitle, 15f, R.color.ink).apply { setPadding(0, dp(6), 0, 0) })
                addView(label(item.detail.ifBlank { "考场待定" }, 14f, R.color.muted).apply { setPadding(0, dp(4), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(MaterialCardView(activity).apply {
                radius = dp(UiStyle.CARD_RADIUS).toFloat(); cardElevation = 0f; strokeWidth = 0; setCardBackgroundColor(color(R.color.card))
                isClickable = true; isFocusable = true; setOnClickListener { onItem(item) }; addView(row)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(UiStyle.CARD_GAP) })
        }
    }
    private fun renderSettings(busy: Boolean, states: Map<CampusSection, FeedState>) {
        if (model.settingsPage != SettingsPage.ROOT) {
            MessageSettings(activity, model) { model.localRevision++; redraw() }.addTo(content)
            return
        }
        heading("校园账户")
        fun connected(sections: List<CampusSection>) = sections.any { section ->
            val state = states.getValue(section)
            state.updatedAt != null && !state.needsLogin && state.error == null
        }
        settingsGroup(listOf(
            Setting("智慧珞珈", "通知、作业、课表", if (connected(CampusSection.entries.filter { it != CampusSection.EXAMS })) "已连接" else "登录", R.id.accounts) { onAccount(false) },
            Setting("本科教务", "考试安排", if (connected(listOf(CampusSection.EXAMS))) "已连接" else "登录", R.id.jwglAccount) { onAccount(true) }))
        heading("偏好")
        val mode = activity.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
            .getInt("mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        settingsGroup(listOf(
            Setting("外观", value = when (mode) { AppCompatDelegate.MODE_NIGHT_NO -> "日间"; AppCompatDelegate.MODE_NIGHT_YES -> "夜间"; else -> "跟随系统" }, id = R.id.appearance, action = onAppearance),
            Setting("显示板块") { activity.openSettingsPage(SettingsPage.DISPLAY) }))
        heading("消息与提醒")
        settingsGroup(listOf(
            Setting("通知显示", value = "来源与排序", id = R.id.messageSettings) { activity.openSettingsPage(SettingsPage.NOTICES) },
            Setting("通知推送", value = "订阅来源") { activity.openSettingsPage(SettingsPage.PUSH) },
            Setting("日程提醒", value = "课程、考试与日程") { activity.openSettingsPage(SettingsPage.REMINDERS) }))
        heading("数据")
        settingsGroup(listOf(
            Setting("导入课程表", value = "系统日历") { activity.importCalendar() },
            Setting("刷新全部", value = if (busy) "更新中" else "", id = R.id.refreshSettings, action = if (busy) null else onRefresh)))
        heading("关于")
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        settingsGroup(listOf(Setting("Velmorath", "校园信息与课程安排", info.versionName.orEmpty())))
    }
    private data class Setting(val title: String, val subtitle: String = "", val value: String = "", val id: Int = View.NO_ID, val action: (() -> Unit)? = null)
    private fun settingsGroup(rows: List<Setting>) {
        val group = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { index, setting ->
            if (index > 0) group.addView(View(activity).apply { setBackgroundColor(color(R.color.line)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(16) })
            group.addView(LinearLayout(activity).apply {
                id = setting.id; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(11), dp(16), dp(11))
                val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(label(setting.title, 16f, R.color.ink))
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(setting.value, UiStyle.SECONDARY, R.color.muted))
                setting.action?.let { action ->
                    addView(label("›", 22f, R.color.muted).apply { setPadding(dp(10), 0, 0, 0) })
                    isClickable = true; isFocusable = true
                    val value = android.util.TypedValue()
                    activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                    setBackgroundResource(value.resourceId)
                    setOnClickListener { action() }
                }
            })
        }
        content.addView(MaterialCardView(activity).apply {
            radius = dp(UiStyle.CARD_RADIUS).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(color(R.color.card))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(UiStyle.CARD_GAP) }
            addView(group)
        })
    }
    private fun card(item: CampusItem, meta: String, detail: String, action: (() -> Unit)? = null, statusColor: Int = R.color.muted, marker: Int? = null): View {
        val card = MaterialCardView(activity).apply {
            radius = dp(UiStyle.CARD_RADIUS).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(color(R.color.card))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(UiStyle.CARD_GAP) }
            isClickable = true; isFocusable = true
            setOnClickListener { if (action != null) action() else onItem(item) }
        }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(UiStyle.CARD_PADDING), dp(UiStyle.CARD_VERTICAL), dp(UiStyle.CARD_PADDING), dp(UiStyle.CARD_VERTICAL)) }
        body.addView(label(item.title, UiStyle.CARD_TITLE, R.color.ink).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            if (marker != null) {
                val dot = ui.shape(marker, 8).apply { setBounds(0, 0, dp(10), dp(10)) }
                setCompoundDrawablesRelative(dot, null, null, null); compoundDrawablePadding = dp(8)
            }
        })
        if (meta.isNotBlank()) body.addView(label(meta, if (item.source == "珞珈在线") 16f else 14f, R.color.muted).apply { setPadding(0, dp(8), 0, 0) })
        if (detail.isNotBlank()) body.addView(label(detail, 13f, statusColor).apply { setPadding(dp(10), dp(5), dp(10), dp(5)); background = ui.shape(color(R.color.canvas), 6) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        card.addView(body)
        return card
    }
    private fun heading(value: String) { content.addView(label(value, 15f, R.color.muted).apply { setPadding(dp(2), dp(14), 0, dp(8)) }) }
    private fun redraw() = render(model.states.value)
    private fun label(value: String, size: Float, id: Int) = TextView(activity).apply { text = value; textSize = size; setTextColor(color(id)); setLineSpacing(dp(3).toFloat(), 1f) }
    private fun text(id: Int, value: String) { activity.findViewById<TextView>(id).text = value }
    private fun visible(id: Int, show: Boolean) { activity.findViewById<View>(id).visibility = if (show) View.VISIBLE else View.GONE }
    private fun color(id: Int) = ContextCompat.getColor(activity, id)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
