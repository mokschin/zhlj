package com.velmorath.app

import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId

object FeedParser {
    private val owners = mapOf("1775598370" to "智慧校园部", "1855807458" to "团委",
        "1602338758" to "研究生院", "1709311821" to "组织部", "1407739091" to "本科生院",
        "1454705279" to "学工部", "1568017242" to "党政办")
    fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    fun date(value: String): LocalDate? = try {
        LocalDate.parse(value.take(10).replace('/', '-'))
    } catch (_: Exception) { null }
    fun webUrl(value: String, base: String = "https://zhlj.whu.edu.cn/"): String = try {
        val uri = URI(base).resolve(value.trim())
        if (value.isNotBlank() && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) uri.toString() else ""
    } catch (_: Exception) { "" }

    fun notices(payload: JSONObject): List<CampusItem> {
        require(payload.optInt("result") == 1) { "通知加载失败" }
        val rows = payload.optJSONArray("data") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val title = row.clean("title")
            if (title.isBlank()) return@mapNotNull null
            val owner = row.clean("ownerId")
            val url = webUrl(row.clean("linkUrl"))
            val host = URI.create(url.ifBlank { "https://zhlj.whu.edu.cn" }).host
            val knownSource = row.clean("ownerName").ifBlank { owners[owner].orEmpty() }
            val published = row.clean("showDateStr").ifBlank { row.clean("showDate").take(10) }
            CampusItem(title, id = url.ifBlank { "$owner|$title|$published" }, url = url,
                source = knownSource.ifBlank { mapOf("uc.whu.edu.cn" to "本科生院", "nic.whu.edu.cn" to "智慧校园部",
                    "gs.whu.edu.cn" to "研究生院", "www.whu.edu.cn" to "武汉大学")[host] ?: host },
                sourceId = if (knownSource.isNotBlank()) owner.ifBlank { knownSource } else host,
                publishedAt = published, content = Jsoup.parse(row.clean("content")).wholeText().trim())
        }
    }

    fun messages(payload: JSONObject): List<CampusItem> {
        require(payload.optInt("result") == 1) { "作业加载失败" }
        val rows = payload.optJSONObject("data")?.optJSONArray("dataList") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            if (row.clean("source") != "珞珈在线") return@mapNotNull null
            val title = row.clean("title").replace(Regex("^(?:作业\\s*[:：]\\s*|【作业】\\s*|\\[作业]\\s*)+"), "").trim()
            if (title.isBlank()) return@mapNotNull null
            val link = sequenceOf("waitLink", "murl", "url").map { webUrl(row.clean(it)) }.firstOrNull { it.isNotBlank() }.orEmpty()
            CampusItem(title, id = row.clean("id").ifBlank { link.ifBlank { title } }, url = link,
                source = "珞珈在线", publishedAt = row.clean("time"),
                dueAt = sequenceOf("endTime", "deadline", "dueTime", "endDate")
                    .map { row.clean(it) }.firstOrNull { date(it) != null }.orEmpty(),
                content = Jsoup.parse(row.clean("content")).wholeText().trim())
        }.distinctBy { it.id }.sortedByDescending { it.publishedAt }
    }

    fun courses(payload: JSONObject): List<CampusItem> {
        require(payload.optInt("result") == 1) { "课表加载失败" }
        val days = payload.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<CampusItem>()
        for (i in 0 until days.length()) {
            val day = days.getJSONObject(i)
            val lessons = day.optJSONArray("curriculumList") ?: continue
            for (j in 0 until lessons.length()) {
                val lesson = lessons.getJSONObject(j)
                val name = lesson.clean("name")
                if (!lesson.optBoolean("hasClass") || name.isBlank()) continue
                val from = lesson.optInt("fromClass")
                val to = lesson.optInt("endClass", from).coerceAtLeast(from)
                val number = lesson.optInt("day", day.optInt("day"))
                if (number !in 1..7 || from !in 1..20 || to !in from..20) continue
                val startWeek = lesson.optInt("fromWeek")
                val endWeek = lesson.optInt("endWeek", startWeek)
                val slot = CourseSlot(number, from, to, lesson.clean("classroom"), lesson.clean("teacher"),
                    if (startWeek > 0) "第 $startWeek–$endWeek 周" else "")
                result += CampusItem(name, detail = listOf(slot.room, slot.teacher).filter { it.isNotBlank() }.joinToString(" · "), course = slot)
            }
        }
        return result.distinctBy { it.title to it.course }.sortedWith(compareBy({ it.course!!.day }, { it.course!!.from }))
    }

    private fun JSONObject.clean(key: String) = if (isNull(key)) "" else optString(key).trim()
}

data class NoticeFilter(val from: LocalDate = FeedParser.today().minusDays(29),
    val until: LocalDate = FeedParser.today(), val sourceIds: Set<String>? = null, val newestFirst: Boolean = true) {
    fun apply(items: List<CampusItem>): List<CampusItem> {
        val selected = items.filter { item ->
            val date = FeedParser.date(item.publishedAt)
            (sourceIds == null || item.sourceId in sourceIds) &&
                ((from == LocalDate.MIN && until == LocalDate.MAX) || (date != null && !date.isBefore(from) && !date.isAfter(until)))
        }
        val sorted = selected.sortedWith(compareByDescending<CampusItem> { FeedParser.date(it.publishedAt) }.thenBy { it.title })
        return if (newestFirst) sorted else sorted.reversed()
    }
}

/** The portal's `start` is a one-based item offset, not a page number. */
internal suspend fun loadRecentNotices(today: LocalDate = FeedParser.today(),
    pause: suspend () -> Unit = { kotlinx.coroutines.delay(3000) },
    fetch: suspend (start: Int, count: Int) -> List<CampusItem>): CampusFeed {
    val cutoff = today.minusDays(29)
    val items = linkedMapOf<String, CampusItem>()
    var offset = 1
    repeat(40) {
        val page = try { fetch(offset, 100) } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (items.isEmpty() || error is SessionExpiredException) throw error
            return CampusFeed(items.values.toList(), partial = true)
        }
        if (page.isEmpty()) return CampusFeed(items.values.toList())
        val before = items.size
        page.filter { item -> FeedParser.date(item.publishedAt)?.let { !it.isBefore(cutoff) && !it.isAfter(today) } ?: true }
            .forEach { items[it.id.ifBlank { "${it.sourceId}|${it.title}|${it.publishedAt}" }] = it }
        if (page.all { FeedParser.date(it.publishedAt)?.isBefore(cutoff) == true }) return CampusFeed(items.values.toList())
        if (items.size == before && page.none { FeedParser.date(it.publishedAt)?.isBefore(cutoff) == true }) {
            return CampusFeed(items.values.toList(), partial = true)
        }
        offset += page.size
        pause()
    }
    return CampusFeed(items.values.toList(), partial = true)
}
