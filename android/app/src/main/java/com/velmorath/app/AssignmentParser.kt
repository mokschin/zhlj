package com.velmorath.app

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AssignmentParser {
    private val stamp = "(20\\d{2}[-/年]\\d{1,2}[-/月]\\d{1,2}日?\\s*[T ]?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?)"
    fun timestamp(value: String): LocalDateTime? {
        val normalized = value.trim().replace('年', '-').replace('月', '-').replace("日", "").replace('/', '-').replace('T', ' ').replace(Regex("\\s+"), " ")
        return listOf("yyyy-M-d H:mm:ss", "yyyy-M-d H:mm").firstNotNullOfOrNull { pattern ->
            try { LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern(pattern)) } catch (_: Exception) { null }
        }
    }
    fun fromNotice(item: CampusItem, data: JSONObject): CampusItem {
        val plain = data.optString("content", "")
        val rich = Jsoup.parse(data.optString("rtf_content", "")).text()
        val content = listOf(plain, rich).filter { it.isNotBlank() }.distinct().joinToString("\n")
        var workUrl = item.workUrl
        val attachments = try { JSONArray(data.optString("attachment", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until attachments.length()) {
            val web = attachments.optJSONObject(i)?.optJSONObject("att_web") ?: continue
            val candidate = FeedParser.webUrl(web.optString("url"))
            if (isWorkUrl(candidate)) { workUrl = candidate; break }
        }
        // Notice `status`, `isread`, `state` and `msgState` describe the message, never submission.
        return fromHtml(item.copy(content = rich.ifBlank { plain }, workUrl = workUrl, detailLoaded = true), content, false)
    }
    fun fromHtml(item: CampusItem, html: String, personal: Boolean): CampusItem {
        val doc = Jsoup.parse(html)
        doc.select("script,style").remove()
        val text = doc.text()
        fun time(labels: String): String = Regex("(?:$labels)\\s*[:：]?\\s*$stamp").find(text)?.groupValues?.get(1).orEmpty()
        val start = time("开始时间|开始日期|开放时间|起始时间|开始于")
        val end = time("结束时间|结束日期|截止时间|截止日期|截止于")
        var submission = item.submission
        if (personal) {
            val evidence = doc.select("[data-submission-status],.work-status,.submission-status,#workStatus")
                .joinToString(" ") { it.attr("data-submission-status") + " " + it.text() }
            val labelled = Regex("(?:提交状态|完成状态|作业状态)\\s*[:：]\\s*(未提交|未完成|已提交|已完成|待批阅|已批阅)").find(text)?.groupValues?.get(1).orEmpty()
            val status = (evidence + " " + labelled).trim()
            submission = when {
                Regex("未提交|未完成").containsMatchIn(status) -> Submission.NOT_SUBMITTED
                Regex("已完成|已批阅").containsMatchIn(status) -> Submission.COMPLETED
                Regex("已提交|待批阅").containsMatchIn(status) -> Submission.SUBMITTED
                else -> submission
            }
        }
        return item.copy(startsAt = start.ifBlank { item.startsAt }, dueAt = end.ifBlank { item.dueAt }, submission = submission)
    }
    fun isWorkUrl(url: String): Boolean = try {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.host?.let { it == "chaoxing.com" || it.endsWith(".chaoxing.com") } == true &&
            Regex("work|exam|task", RegexOption.IGNORE_CASE).containsMatchIn(uri.path.orEmpty() + "?" + uri.query.orEmpty()) && !uri.path.contains("login", true)
    } catch (_: Exception) { false }
    fun availability(item: CampusItem, now: LocalDateTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))): String = when {
        timestamp(item.startsAt)?.isAfter(now) == true -> "未开始"
        timestamp(item.dueAt)?.isBefore(now) == true -> "已结束"
        timestamp(item.startsAt)?.isAfter(now) == false -> "进行中"
        else -> ""
    }
}
