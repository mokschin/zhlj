package com.velmorath.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.*
import java.time.format.DateTimeFormatter

internal class ScheduleEditor(private val activity: MainActivity, private val saved: (PersonalEvent?, String?) -> Unit) {
    fun show(existing: PersonalEvent? = null, date: LocalDate = FeedParser.today()) {
        val ui = UiKit(activity)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        fun input(hint: String, initial: String) = EditText(activity).apply { this.hint = hint; setText(initial); setSingleLine(); textSize = 16f; body.addView(this) }
        val name = input("日程名称", existing?.name.orEmpty())
        val tag = input("标签（可选）", existing?.tag.orEmpty())
        val place = input("地点（可选）", existing?.place.orEmpty())
        val id = existing?.id ?: java.util.UUID.randomUUID().toString()
        var start = existing?.start ?: (if (date == FeedParser.today()) LocalDateTime.now(CourseTimes.zone).plusHours(1).withMinute(0).withSecond(0).withNano(0) else date.atTime(9, 0))
        var end = existing?.end ?: start.plusHours(1)
        var color = existing?.color ?: StableColors.next(activity.getSharedPreferences("stable_colors", Context.MODE_PRIVATE).all.filterKeys { !it.startsWith("source:") }.values.filterIsInstance<Int>().toSet())
        val format = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")
        val startButton = ui.action("") {}; val endButton = ui.action("") {}
        fun labels() { startButton.text = "开始  ${start.format(format)}"; endButton.text = "结束  ${end.format(format)}" }
        fun pick(value: LocalDateTime, update: (LocalDateTime) -> Unit) {
            DatePickerDialog(activity, { _, y, m, d -> TimePickerDialog(activity, { _, h, min -> update(LocalDate.of(y, m + 1, d).atTime(h, min)); labels() }, value.hour, value.minute, true).show() }, value.year, value.monthValue - 1, value.dayOfMonth).show()
        }
        startButton.setOnClickListener { pick(start) { val duration = Duration.between(start, end); start = it; if (end <= start) end = start.plus(duration) } }
        endButton.setOnClickListener { pick(end) { end = it } }
        body.addView(startButton); body.addView(endButton); labels()
        val colorButton = ui.action("颜色 · 自动分配") {}
        fun colorLabel() { colorButton.setTextColor(color); colorButton.text = "●  日程颜色" }
        colorButton.setOnClickListener {
            val swatches = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), 0, ui.dp(16), 0) }
            val chooser = MaterialAlertDialogBuilder(activity).setTitle("选择颜色").setView(swatches).setNegativeButton("取消", null).create()
            StableColors.palette.chunked(4).forEach { colors ->
                val row = LinearLayout(activity)
                colors.forEach { value -> row.addView(ui.action("●") {}.apply { textSize = 28f; setTextColor(value); setOnClickListener { color = value; colorLabel(); chooser.dismiss() } }, LinearLayout.LayoutParams(0, ui.dp(48), 1f)) }
                swatches.addView(row)
            }
            swatches.addView(ui.action("自动选择未占用颜色") {
                val prefs = activity.getSharedPreferences("stable_colors", Context.MODE_PRIVATE)
                color = StableColors.next(prefs.all.filterKeys { it != "personal:$id" && !it.startsWith("source:") }.values.filterIsInstance<Int>().toSet()); colorLabel(); chooser.dismiss()
            })
            chooser.show()
        }
        colorLabel(); body.addView(colorButton)
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(if (existing == null) "新建日程" else "编辑日程")
            .setView(ScrollView(activity).apply { addView(body) }).setPositiveButton("保存", null).setNegativeButton("取消", null)
            .apply { if (existing != null) setNeutralButton("删除", null) }.create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                if (name.text.isBlank()) { name.error = "请输入名称"; return@setOnClickListener }
                if (end <= start) { Toast.makeText(activity, "结束时间须晚于开始时间", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                saved(PersonalEvent(id, name.text.toString().trim(), tag.text.toString().trim(), start, end, place.text.toString().trim(), color), null); dialog.dismiss()
            }
            if (existing != null) dialog.getButton(-3).setOnClickListener { MaterialAlertDialogBuilder(activity).setTitle("删除此日程？").setPositiveButton("删除") { _, _ -> saved(null, id); dialog.dismiss() }.setNegativeButton("取消", null).show() }
        }
        dialog.show()
    }
}
