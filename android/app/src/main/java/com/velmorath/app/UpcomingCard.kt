package com.velmorath.app

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import com.google.android.material.card.MaterialCardView
import java.time.*

internal data class UpcomingEvent(val item: CampusItem, val date: LocalDate, val start: LocalDateTime, val end: LocalDateTime, val time: String, val room: String, val exam: Boolean) {
    val custom get() = item.personal != null
    val key get() = if (custom) "personal:${item.personal!!.id}" else "${if (exam) "exam" else "course"}:${item.title}:$start:$end"
    companion object {
        fun all(feeds: List<CampusFeed>, exams: CampusFeed?, personal: List<PersonalEvent>): List<UpcomingEvent> {
            val courses = feeds.flatMap { feed ->
                val monday = FeedParser.date(feed.weekStart)
                if (monday == null) emptyList() else feed.items.mapNotNull { item ->
                    val slot = item.course ?: return@mapNotNull null
                    val date = monday.plusDays((slot.day - 1).toLong())
                    val range = CourseTimes.range(date, slot) ?: return@mapNotNull null
                    UpcomingEvent(item, date, range.first, range.second, CourseTimes.label(slot), slot.room, false)
                }
            }
            val tests = exams?.items.orEmpty().mapNotNull { item ->
                val date = FeedParser.date(item.subtitle) ?: return@mapNotNull null
                val times = Regex("\\d{1,2}:\\d{2}").findAll(item.subtitle).map { it.value }.toList()
                fun at(value: String) = runCatching { date.atTime(LocalTime.parse(value.padStart(5, '0'))) }.getOrNull()
                val start = times.firstOrNull()?.let(::at) ?: return@mapNotNull null
                var end = times.getOrNull(1)?.let(::at) ?: return@mapNotNull null
                if (end <= start) end = end.plusDays(1)
                UpcomingEvent(item, date, start, end, times.joinToString("–"), item.detail, true)
            }
            return (courses + tests + personal.map { UpcomingEvent(it.item(), it.start.toLocalDate(), it.start, it.end,
                "${it.start.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}–${it.end.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}", it.place, false) }).distinctBy { it.key }.sortedBy { it.start }
        }
        fun select(events: List<UpcomingEvent>, now: LocalDateTime): List<UpcomingEvent> {
            val live = events.filter { it.end > now }
            val courses = live.filter { !it.exam && !it.custom && it.start <= now.plusHours(6) }.take(1)
            val custom = live.filter { it.custom && it.start <= now.plusHours(6) }.take(1)
            val exams = live.filter { it.exam && it.start <= now.plusHours(48) }
            return (courses + custom + exams).sortedBy { it.start }
        }
        fun collect(model: DashboardViewModel, states: Map<CampusSection, FeedState>, now: LocalDateTime = LocalDateTime.now(CourseTimes.zone)): List<UpcomingEvent> =
            select(all(model.allCourseFeeds() + listOfNotNull(states.getValue(CampusSection.COURSES).feed), states.getValue(CampusSection.EXAMS).feed, model.personalEvents), now)
    }
}

internal class UpcomingCard(context: Context, private val event: UpcomingEvent, click: () -> Unit) : MaterialCardView(context) {
    private val ui = UiKit(context)
    private val countdown = ui.text("", 20f, bold = true)
    private val countdownLabel = ui.text("", 12f, R.color.muted)
    private val progress = com.google.android.material.progressindicator.LinearProgressIndicator(context)
    private val tick = object : Runnable {
        override fun run() {
            val now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
            val active = now >= event.start
            val seconds = Duration.between(now, if (active) event.end else event.start).seconds.coerceAtLeast(0)
            countdownLabel.text = if (now >= event.end) "已结束" else if (active) { if (event.exam || event.custom) "距离结束" else "距离下课" } else "距离开始"
            countdown.text = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            val total = if (active) Duration.between(event.start, event.end).seconds.coerceAtLeast(1) else if (event.exam) 172800L else 21600L
            progress.progress = (100 - seconds.coerceIn(0, total) * 100 / total).toInt()
            if (isAttachedToWindow && seconds > 0) postDelayed(this, 1000)
        }
    }
    init {
        radius = ui.dp(UiStyle.CARD_RADIUS).toFloat(); cardElevation = 0f; strokeWidth = 0; setCardBackgroundColor(ui.color(R.color.card))
        isClickable = true; isFocusable = true; setOnClickListener { click() }
        val accent = if (event.exam) android.graphics.Color.rgb(240, 68, 82) else StableColors.item(context, event.item)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(UiStyle.CARD_PADDING), ui.dp(UiStyle.CARD_VERTICAL), ui.dp(UiStyle.CARD_PADDING), ui.dp(UiStyle.CARD_VERTICAL)) }
        val top = LinearLayout(context).apply { gravity = Gravity.TOP }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ui.text(event.item.title, UiStyle.CARD_TITLE, bold = true).apply {
            val dot = ui.shape(accent, if (event.exam) 0 else 6).apply { setBounds(0, 0, ui.dp(12), ui.dp(12)) }
            setCompoundDrawablesRelative(dot, null, null, null); compoundDrawablePadding = ui.dp(8)
        })
        if (event.custom && event.item.personal!!.tag.isNotBlank()) left.addView(ui.text(event.item.personal.tag, 12f, R.color.muted))
        left.addView(ui.text(event.room.ifBlank { "地点待定" }, 14f).apply { setPadding(0, ui.dp(10), 0, 0) })
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; setPadding(ui.dp(8), 0, 0, 0) }
        val today = FeedParser.today()
        val day = when(event.date) { today -> "今天"; today.plusDays(1) -> "明天"; else -> "${event.date.monthValue}/${event.date.dayOfMonth}" }
        right.addView(ui.text(if (event.exam) "$day ${event.time}" else event.time, 13f, R.color.muted))
        right.addView(countdownLabel)
        right.addView(countdown)
        top.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(top)
        progress.setIndicatorColor(accent); progress.trackColor = ui.color(R.color.line); progress.trackCornerRadius = ui.dp(3); progress.trackThickness = ui.dp(5)
        body.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(5)).apply { topMargin = ui.dp(10) })
        addView(body)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); tick.run() }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
}
