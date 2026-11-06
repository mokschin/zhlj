package com.velmorath.app

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** Month selection above a daily agenda, following Celechron's calendar interaction. */
@SuppressLint("ViewConstructor")
class CourseCalendarView(context: Context, private val month: YearMonth, private val selected: LocalDate,
    events: (LocalDate) -> List<CampusItem>, private val onDate: (LocalDate) -> Unit,
    private val onMonth: (YearMonth) -> Unit) : LinearLayout(context) {
    private val ui = UiKit(context)
    private var startX = 0f; private var startY = 0f
    init {
        orientation = VERTICAL
        val weekdays = LinearLayout(context)
        "一二三四五六日".forEach { day -> weekdays.addView(ui.text(day.toString(), 16f, R.color.muted).apply { gravity = Gravity.CENTER; minHeight = ui.dp(36) }, LayoutParams(0, -2, 1f)) }
        addView(weekdays)
        val first = month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value - 1).toLong())
        val rowCount = (month.lengthOfMonth() + month.atDay(1).dayOfWeek.value - 2) / 7 + 1
        for (row in 0 until rowCount) {
            val line = LinearLayout(context)
            for (column in 0..6) {
                val date = first.plusDays((row * 7 + column).toLong())
                val cell = LinearLayout(context).apply {
                    orientation = VERTICAL; gravity = Gravity.CENTER
                    contentDescription = date.toString(); isClickable = true; isFocusable = true
                    setOnClickListener { onDate(date) }
                }
                val number = ui.text(date.dayOfMonth.toString(), 17f,
                    if (YearMonth.from(date) != month || date.dayOfWeek.value >= 6) R.color.muted else R.color.ink, date == selected).apply {
                    gravity = Gravity.CENTER; includeFontPadding = false; setLineSpacing(0f, 1f)
                    if (date == selected) { background = ui.shape(ui.color(R.color.accent), 20); setTextColor(ui.color(R.color.card)) }
                    else if (date == FeedParser.today()) background = ui.shape(ui.color(R.color.line), 20)
                }
                cell.addView(number, LayoutParams(ui.dp(30), ui.dp(30)))
                val dots = LinearLayout(context).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false }
                events(date).take(6).forEach { item ->
                    dots.addView(View(context).apply { background = ui.shape(StableColors.item(context, item), 3) }, LayoutParams(ui.dp(4), ui.dp(4)).apply { setMargins(ui.dp(1), 0, ui.dp(1), 0) })
                }
                cell.addView(dots, LayoutParams(-1, ui.dp(6)))
                line.addView(cell, LayoutParams(0, ui.dp(46), 1f))
            }
            addView(line)
        }
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { startX = event.x; startY = event.y }
        return event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.x - startX) > ui.dp(32) && abs(event.x - startX) > abs(event.y - startY) * 1.5
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (abs(event.x - startX) > ui.dp(32)) {
                val direction = if (event.x > startX) -1L else 1L
                onMonth(month.plusMonths(direction))
            }
        }
        return true
    }
}
