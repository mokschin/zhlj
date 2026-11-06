package com.velmorath.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Fixed period axis, Monday–Sunday columns, and merged conflict blocks. */
@android.annotation.SuppressLint("ViewConstructor")
class TimetableView(context: Context, private val feed: CampusFeed, private val onCourse: (List<CampusItem>) -> Unit) : FrameLayout(context) {
    private var lastWidth = 0
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0 && width != lastWidth) { lastWidth = width; removeAllViews(); build(width) }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun build(width: Int) {
        contentDescription = "周课表，周一至周日，点击课程查看详情"
        val rowHeight = dp(40)
        val axis = dp(33)
        val columnWidth = (width - axis) / 7
        val maxPeriod = maxOf(13, feed.items.maxOfOrNull { it.course?.to ?: 0 } ?: 13)
        val table = FrameLayout(context)
        table.layoutParams = LayoutParams(axis + columnWidth * 7, dp(50) + rowHeight * maxPeriod)
        val start = FeedParser.date(feed.weekStart)
        for (day in 1..7) {
            val date = start?.plusDays((day - 1).toLong())
            val today = date == FeedParser.today()
            val header = text(listOf("${"一二三四五六日"[day - 1]}", date?.let { "${it.monthValue}/${it.dayOfMonth}" }.orEmpty())
                .filter { it.isNotBlank() }.joinToString("\n"), 11f,
                if (today) R.color.accent else R.color.muted).apply {
                gravity = Gravity.CENTER
                if (today) setTypeface(typeface, Typeface.BOLD)
            }
            table.addView(header, FrameLayout.LayoutParams(columnWidth, dp(50)).apply { leftMargin = axis + (day - 1) * columnWidth })

        }
        for (period in 1..maxPeriod) {
            table.addView(text(CourseTimes.starts.getOrNull(period - 1) ?: "$period", 9f, R.color.muted).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL },
                FrameLayout.LayoutParams(axis, rowHeight).apply { topMargin = dp(50) + (period - 1) * rowHeight + dp(5) })
            table.addView(View(context).apply { setBackgroundColor(color(R.color.line)) },
                FrameLayout.LayoutParams(columnWidth * 7, dp(1)).apply { leftMargin = axis; topMargin = dp(50) + (period - 1) * rowHeight })
        }
        for (day in 1..7) {
            val groups = mutableListOf<MutableList<CampusItem>>()
            feed.items.filter { it.course?.day == day }.sortedBy { it.course!!.from }.forEach { item ->
                val last = groups.lastOrNull()
                if (last != null && item.course!!.from <= last.maxOf { it.course!!.to }) last += item
                else groups += mutableListOf(item)
            }
            groups.forEach { items ->
                val from = items.minOf { it.course!!.from }
                val to = items.maxOf { it.course!!.to }
                val palette = CoursePalette.colors(context, items.first().title)
                val body = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(3), dp(5), dp(3), dp(3))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(palette.first); setStroke(dp(1), palette.second)
                    }
                    isClickable = true; isFocusable = true
                    contentDescription = items.joinToString("；") { "${it.title}，${CourseTimes.label(it.course!!)}，${it.course.room}" }
                    setOnClickListener { onCourse(items) }
                }
                if (items.size > 1) body.addView(text("冲突", 10f, R.color.accent))
                body.addView(text(items.joinToString(" / ") { it.title }, 10.5f, R.color.ink).apply {
                    setTextColor(color(R.color.ink))
                    setTypeface(typeface, Typeface.BOLD); maxLines = if (to > from) 4 else 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (items.size == 1 && to > from) body.addView(text(items.first().course!!.room.ifBlank { "地点待定" }, 10f, R.color.muted).apply {
                    setTextColor(color(R.color.ink))
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                table.addView(body, FrameLayout.LayoutParams(columnWidth - dp(2), (to - from + 1) * rowHeight - dp(5)).apply {
                    leftMargin = axis + (day - 1) * columnWidth + dp(1)
                    topMargin = dp(50) + (from - 1) * rowHeight + dp(3)
                })
            }
        }
        addView(table)
    }
    private fun text(value: String, size: Float, id: Int) = TextView(context).apply { text = value; textSize = size; setTextColor(color(id)) }
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
