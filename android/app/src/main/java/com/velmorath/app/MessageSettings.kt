package com.velmorath.app

import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder

enum class SettingsPage(val title: String) {
    ROOT("设置"), DISPLAY("显示板块"), NOTICES("通知显示"), PUSH("通知推送"), SOURCES("订阅来源"), REMINDERS("日程提醒");
    val parent: SettingsPage get() = if (this == SOURCES) PUSH else ROOT
}

/** Settings use the same compact, divided rows on every level. */
internal class MessageSettings(private val activity: MainActivity, private val model: DashboardViewModel, private val changed: () -> Unit) {
    private val ui = UiKit(activity)
    private val p = Reminders.prefs(activity)
    private lateinit var content: LinearLayout
    private val sources get() = (ScheduleStore(activity).sources() + model.states.value.getValue(CampusSection.NOTICES).feed?.items.orEmpty().associate { it.sourceId to it.source }).toList().sortedBy { it.second }
    private fun hint(value: String) { content.addView(ui.text(value, 13f, R.color.muted).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(12)) }) }
    private fun group(vararg rows: View) {
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { index, view ->
            if (index > 0) body.addView(View(activity).apply { setBackgroundColor(ui.color(R.color.line)) }, LinearLayout.LayoutParams(-1, ui.dp(1)).apply { leftMargin = ui.dp(16); rightMargin = ui.dp(16) })
            body.addView(view)
        }
        content.addView(MaterialCardView(activity).apply {
            radius = ui.dp(UiStyle.CARD_RADIUS).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(ui.color(R.color.card)); addView(body)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(4) })
    }
    private fun row(title: String, value: String = "", action: () -> Unit): View = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(52); setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        addView(ui.text(title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.text(value, 14f, R.color.muted)); addView(ui.text("›", 22f, R.color.muted).apply { setPadding(ui.dp(10), 0, 0, 0) })
        val attr = android.util.TypedValue(); activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, attr, true)
        setBackgroundResource(attr.resourceId); isFocusable = true; setOnClickListener { action() }
    }
    private fun toggle(title: String, checked: Boolean, action: (Boolean) -> Unit) = MaterialSwitch(activity).apply {
        text = title; textSize = 16f; setTextColor(ui.color(R.color.ink)); minHeight = ui.dp(52)
        setPadding(ui.dp(16), ui.dp(3), ui.dp(16), ui.dp(3)); isChecked = checked
        setOnCheckedChangeListener { _, value -> action(value) }
    }
    private fun reminderToggle(key: String, title: String) = toggle(title, p.getBoolean(key, false)) { checked ->
        p.edit().putBoolean(key, checked).apply()
        if (key == "notice" && checked) p.edit().putBoolean("notice_baseline", false).apply()
        if (checked) activity.requestNotificationAccess()
        Reminders.configureWorker(activity); Reminders.schedule(activity)
    }
    private fun check(title: String, checked: Boolean, source: String? = null, action: (Boolean) -> Unit): View = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(52); setPadding(ui.dp(16), 0, ui.dp(8), 0)
        addView(ui.text(title, 16f).apply {
            if (source != null) {
                val dot = ui.shape(StableColors.get(activity, "source", source), 8).apply { setBounds(0, 0, ui.dp(9), ui.dp(9)) }
                setCompoundDrawablesRelative(dot, null, null, null); compoundDrawablePadding = ui.dp(10)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val checkbox = MaterialCheckBox(activity).apply { contentDescription = title; isChecked = checked; setOnCheckedChangeListener { _, value -> action(value) } }
        addView(checkbox); setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
    }
    private fun saveFilter(ids: Set<String>?) {
        model.noticeFilter = model.noticeFilter.copy(sourceIds = ids)
        p.edit().remove("filter_source").apply { if (ids == null) remove("filter_sources") else putStringSet("filter_sources", ids) }.apply()
        changed()
    }
    fun addTo(content: LinearLayout) {
        this.content = content
        when (model.settingsPage) {
            SettingsPage.DISPLAY -> {
                group(*activity.sectionOptions().map { (key, name) -> toggle(name, activity.sectionEnabled(key)) { activity.setSectionEnabled(key, it) } }.toTypedArray())
                hint("选择底部导航中显示的板块。设置始终保留。")
            }
            SettingsPage.NOTICES -> {
                group(row("排序", if (model.noticeFilter.newestFirst) "最新优先" else "最早优先") {
                    MaterialAlertDialogBuilder(activity).setTitle("通知排序")
                        .setSingleChoiceItems(arrayOf("最新优先", "最早优先"), if (model.noticeFilter.newestFirst) 0 else 1) { dialog, index ->
                            model.noticeFilter = model.noticeFilter.copy(newestFirst = index == 0)
                            p.edit().putBoolean("newest", index == 0).apply(); dialog.dismiss(); changed()
                        }.setNegativeButton("取消", null).show()
                })
                hint("查看来源 · 可多选，不影响推送订阅")
                val ids = model.noticeFilter.sourceIds
                group(check("全部来源", ids == null) { saveFilter(if (it) null else emptySet()) },
                    *sources.map { (id, name) -> check(name, ids == null || id in ids, id) { selected ->
                        val next = (model.noticeFilter.sourceIds ?: sources.map { it.first }.toSet()).toMutableSet()
                        if (selected) next.add(id) else next.remove(id)
                        saveFilter(next)
                    } }.toTypedArray())
                if (sources.isEmpty()) hint("刷新通知后可选择各个来源。")
            }
            SettingsPage.PUSH -> {
                group(reminderToggle("notice", "通知推送"), row("订阅来源", "已选 ${p.getStringSet("notice_sources", emptySet()).orEmpty().size} 项") { activity.openSettingsPage(SettingsPage.SOURCES) })
                hint("只推送订阅来源的新通知，首次订阅不推送历史内容。约每 30 分钟检查一次。")
                group(row("系统通知权限") { activity.requestNotificationAccess(openSettings = true) })
            }
            SettingsPage.SOURCES -> {
                hint("订阅来源 · 可多选")
                val selected = p.getStringSet("notice_sources", emptySet()).orEmpty()
                if (sources.isEmpty()) hint("请先刷新通知以获取来源。")
                else group(*sources.map { (id, name) -> check(name, id in selected, id) { enabled ->
                    val next = p.getStringSet("notice_sources", emptySet()).orEmpty().toMutableSet()
                    if (enabled) next.add(id) else next.remove(id)
                    p.edit().putStringSet("notice_sources", next).putBoolean("notice_baseline", false).apply()
                    Reminders.configureWorker(activity)
                } }.toTypedArray())
            }
            SettingsPage.REMINDERS -> {
                listOf("course" to "课程", "exam" to "考试", "personal" to "自定义日程").forEach { (key, title) ->
                    group(reminderToggle(key, "${title}提醒"), row("提前提醒", "${Reminders.lead(activity, key)} 分钟") { editLead(key, title) })
                }
                hint("按日程开始时间提前提醒。")
                group(row("系统通知权限") { activity.requestNotificationAccess(openSettings = true) })
                if (Build.VERSION.SDK_INT >= 31 && !activity.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                    group(row("允许准时提醒") { activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}"))) })
                    hint("未授权时，系统可能延后提醒。")
                }
            }
            SettingsPage.ROOT -> Unit
        }
    }
    private fun editLead(key: String, title: String) {
        val input = EditText(activity).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(Reminders.lead(activity, key).toString()); selectAll() }
        val frame = FrameLayout(activity).apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0); addView(input) }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("${title}提前提醒").setMessage("提前多少分钟提醒（0–10080）").setView(frame).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value !in 0..10080) input.error = "请输入 0–10080" else {
                p.edit().putInt("${key}_minutes", value).apply(); Reminders.schedule(activity); dialog.dismiss(); changed()
            }
        } }; dialog.show()
    }
}
