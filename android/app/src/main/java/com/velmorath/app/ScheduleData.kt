package com.velmorath.app

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

object CourseTimes {
    val starts = listOf("08:00", "08:50", "09:50", "10:40", "11:30", "14:05", "14:55", "15:45", "16:40", "17:30", "18:30", "19:20", "20:10")
    val ends = listOf("08:45", "09:35", "10:35", "11:25", "12:15", "14:50", "15:40", "16:30", "17:25", "18:15", "19:15", "20:05", "20:55")
    fun label(slot: CourseSlot) = if (slot.from in 1..13 && slot.to in 1..13) "${starts[slot.from - 1]}–${ends[slot.to - 1]}" else "时间待确认"
    fun range(date: LocalDate, slot: CourseSlot): Pair<LocalDateTime, LocalDateTime>? = if (slot.from in 1..13 && slot.to in slot.from..13)
        date.atTime(LocalTime.parse(starts[slot.from - 1])) to date.atTime(LocalTime.parse(ends[slot.to - 1])) else null
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
}

data class PersonalEvent(val id: String = java.util.UUID.randomUUID().toString(), val name: String, val tag: String,
    val start: LocalDateTime, val end: LocalDateTime, val place: String, val color: Int) {
    fun item() = CampusItem(name, id = "personal:$id", startsAt = start.toString(), dueAt = end.toString(), detail = place, personal = this)
}

internal object StableColors {
    val palette = listOf("#A17BE0", "#DE9A36", "#4CA698", "#598CD8", "#D7799E", "#86AA4E", "#B18B68", "#6CAEC5", "#CD795A", "#9583B7", "#C19F59", "#669484").map(Color::parseColor)
    @Synchronized fun get(context: Context, namespace: String, key: String): Int {
        val prefs = context.getSharedPreferences("stable_colors", Context.MODE_PRIVATE)
        val id = "$namespace:$key"
        if (prefs.contains(id)) return prefs.getInt(id, palette.first())
        val used = prefs.all.filterKeys { if (namespace == "source") it.startsWith("source:") else !it.startsWith("source:") }.values.filterIsInstance<Int>().toSet()
        val color = next(used)
        prefs.edit().putInt(id, color).apply()
        return color
    }
    fun next(used: Set<Int>): Int {
        palette.firstOrNull { it !in used }?.let { return it }
        var index = 0
        while (true) {
            val value = Color.HSVToColor(floatArrayOf((index++ * 137.508f) % 360, .55f, .78f))
            if (value !in used) return value
        }
    }
    fun reserve(context: Context, id: String, color: Int) = context.getSharedPreferences("stable_colors", Context.MODE_PRIVATE).edit().putInt("personal:$id", color).apply()
    fun personal(context: Context, id: String) = get(context, "personal", id)
    fun item(context: Context, item: CampusItem) = item.personal?.color ?: get(context, "course", item.title)
}

internal class ScheduleStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("schedule_data", Context.MODE_PRIVATE)
    private fun array(key: String) = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
    fun personal(): List<PersonalEvent> = (0 until array("personal").length()).mapNotNull { index -> runCatching {
        val o = array("personal").getJSONObject(index)
        PersonalEvent(o.getString("id"), o.getString("name"), o.optString("tag"), LocalDateTime.parse(o.getString("start")), LocalDateTime.parse(o.getString("end")), o.optString("place"), o.getInt("color"))
    }.getOrNull() }
    fun savePersonal(events: List<PersonalEvent>) {
        prefs.edit().putString("personal", JSONArray(events.map { JSONObject().put("id", it.id).put("name", it.name).put("tag", it.tag).put("start", it.start).put("end", it.end).put("place", it.place).put("color", it.color) }).toString()).apply()
        events.forEach { StableColors.reserve(context, it.id, it.color) }
    }
    fun feeds(): List<CampusFeed> = (0 until array("weeks").length()).mapNotNull { index -> runCatching {
        val o = array("weeks").getJSONObject(index)
        CampusFeed(decodeItems(o.getJSONArray("items")), weekStart = o.getString("week"))
    }.getOrNull() }
    fun exams() = CampusFeed(decodeItems(array("exams")))
    @Synchronized fun save(feeds: List<CampusFeed>, exams: CampusFeed?) {
        val merged = (this.feeds() + feeds).filter { it.weekStart.isNotBlank() }.associateBy { it.weekStart }.values.sortedBy { it.weekStart }.takeLast(60)
        prefs.edit().putString("weeks", JSONArray(merged.map { JSONObject().put("week", it.weekStart).put("items", encodeItems(it.items)) }).toString()).apply()
        if (exams != null) prefs.edit().putString("exams", encodeItems(exams.items).toString()).apply()
    }
    fun sources(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString("sources", "{}")!!); o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())
    fun rememberSources(items: List<CampusItem>) {
        val map = sources() + items.associate { it.sourceId to it.source }
        prefs.edit().putString("sources", JSONObject(map).toString()).apply()
    }
    companion object {
        fun encodeItems(items: List<CampusItem>) = JSONArray(items.map { item ->
            JSONObject().put("title", item.title).put("subtitle", item.subtitle).put("detail", item.detail).put("id", item.id).apply {
                item.course?.let { put("course", JSONObject().put("day", it.day).put("from", it.from).put("to", it.to).put("room", it.room).put("teacher", it.teacher).put("weeks", it.weeks)) }
            }
        })
        fun decodeItems(a: JSONArray): List<CampusItem> = (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i); val c = o.optJSONObject("course")
            CampusItem(o.getString("title"), o.optString("subtitle"), o.optString("detail"), o.optString("id"), course = c?.let { CourseSlot(it.getInt("day"), it.getInt("from"), it.getInt("to"), it.optString("room"), it.optString("teacher"), it.optString("weeks")) })
        }.getOrNull() }
    }
}
