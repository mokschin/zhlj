package com.velmorath.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object ZhljApi {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36"
    private const val PORTAL_REFERER = "https://zhlj.whu.edu.cn/pc/toIndex"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val allowedOwners = setOf(
        "1775598370", // 智慧校园部
        "1855807458", // 团委
        "1602338758", // 研究生院
        "1709311821", // 组织部
    )

    private fun get(url: String, cookie: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("Referer", referer)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/html, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun postJson(
        url: String,
        body: JSONObject,
        cookie: String,
        referer: String,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("Referer", referer)
            .header("User-Agent", UA)
            .header("Accept", "application/json, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun postForm(
        url: String,
        form: FormBody,
        cookie: String,
        referer: String,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("Referer", referer)
            .header("User-Agent", UA)
            .header("Accept", "application/json, */*; q=0.01")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    suspend fun notices(cookie: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("start", 1).put("count", 20)
        val text = postJson(
            "https://zhlj.whu.edu.cn/whdxIndex/getNotice",
            body,
            cookie,
            PORTAL_REFERER,
        )
        val payload = JSONObject(text)
        if (payload.optInt("result") != 1) {
            throw RuntimeException(payload.optString("msg", "通知接口失败"))
        }
        val data = payload.optJSONArray("data") ?: JSONArray()
        val out = StringBuilder()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            if (item.optString("ownerId") !in allowedOwners) continue
            out.append(item.optString("showDateStr"))
                .append("  ")
                .append(item.optString("title"))
                .append('\n')
        }
        out.toString().trim().ifEmpty { "暂无通知" }
    }

    suspend fun messages(cookie: String): String = withContext(Dispatchers.IO) {
        val text = get(
            "https://zhlj.whu.edu.cn/whdxIndex/getMessage?type=1",
            cookie,
            PORTAL_REFERER,
        )
        val payload = JSONObject(text)
        val list = payload.optJSONObject("data")?.optJSONArray("dataList") ?: JSONArray()
        val out = StringBuilder()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.optString("source") != "珞珈在线") continue
            out.append(item.optString("time"))
                .append("  ")
                .append(item.optString("title"))
                .append('\n')
        }
        out.toString().trim().ifEmpty { "暂无作业" }
    }

    suspend fun courses(cookie: String): String = withContext(Dispatchers.IO) {
        val termsText = get(
            "https://zhlj.whu.edu.cn/whdxSchedule/getNearSchoolTerm",
            cookie,
            PORTAL_REFERER,
        )
        val terms = JSONObject(termsText).optJSONArray("data") ?: JSONArray()
        var term: JSONObject? = null
        for (i in 0 until terms.length()) {
            val candidate = terms.getJSONObject(i)
            if (candidate.optInt("nowTerm") == 1) {
                term = candidate
                break
            }
        }
        val current = term ?: throw RuntimeException("没有当前学期")
        val start = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .parse(current.optString("termStartTime"))
            ?: throw RuntimeException("学期开始日期无效")
        val week = ((Date().time - start.time) / (7L * 24 * 3600 * 1000)).toInt() + 1
        val boundedWeek = week.coerceIn(1, current.optInt("termWeekCount", week))
        val calendar = Calendar.getInstance().apply {
            time = start
            add(Calendar.DAY_OF_YEAR, (boundedWeek - 1) * 7 + 1)
        }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
        val text = get(
            "https://zhlj.whu.edu.cn/whdxIndex/getCurriculum?date=$date",
            cookie,
            PORTAL_REFERER,
        )
        val payload = JSONObject(text)
        if (payload.optInt("result") != 1) throw RuntimeException("课表接口失败")
        val days = payload.optJSONArray("data") ?: JSONArray()
        val out = StringBuilder("第 $boundedWeek 周\n")
        for (i in 0 until days.length()) {
            val day = days.getJSONObject(i)
            val lessons = day.optJSONArray("curriculumList") ?: continue
            for (j in 0 until lessons.length()) {
                val lesson = lessons.getJSONObject(j)
                if (!lesson.optBoolean("hasClass")) continue
                val name = lesson.optString("name")
                if (name.isBlank()) continue
                out.append("周")
                    .append(day.optInt("day"))
                    .append(" 第")
                    .append(lesson.optInt("fromClass"))
                    .append("-")
                    .append(lesson.optInt("endClass"))
                    .append("节  ")
                    .append(name)
                    .append("  ")
                    .append(lesson.optString("teacher"))
                    .append("  ")
                    .append(lesson.optString("classroom"))
                    .append('\n')
            }
        }
        out.toString().trim()
    }

    suspend fun exams(cookie: String): String = withContext(Dispatchers.IO) {
        val pageUrl =
            "https://jwgl.whu.edu.cn/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105&layout=default"
        val page = get(pageUrl, cookie, "https://jwgl.whu.edu.cn/xtgl/index_initMenu.html")
        val xnm = selectedOption(page, "cx_xnm")
        val xqm = selectedOption(page, "cx_xqm")
        if (xnm.isBlank() || xqm.isBlank()) throw RuntimeException("无法解析学年/学期")
        val form = FormBody.Builder()
            .add("xnm", xnm)
            .add("xqm", xqm)
            .add("ksmcdmb_id", "")
            .add("kch", "")
            .add("kc", "")
            .add("ksrq", "")
            .add("kkbm_id", "")
            .add("_search", "false")
            .add("nd", System.currentTimeMillis().toString())
            .add("queryModel.showCount", "100")
            .add("queryModel.currentPage", "1")
            .add("queryModel.sortName", "")
            .add("queryModel.sortOrder", "asc")
            .add("time", "1")
            .build()
        val text = postForm(
            "https://jwgl.whu.edu.cn/kwgl/kscx_cxXsksxxIndex.html?doType=query",
            form,
            cookie,
            pageUrl,
        )
        val items = JSONObject(text).optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) return@withContext "暂无考试安排"
        val out = StringBuilder()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            out.append(item.optString("ksrq"))
                .append(' ')
                .append(item.optString("kssj"))
                .append("  ")
                .append(item.optString("kcmc"))
                .append("  ")
                .append(item.optString("cdmc"))
                .append('\n')
        }
        out.toString().trim()
    }

    private fun selectedOption(html: String, id: String): String {
        val regex = Regex(
            "id=\"$id\".*?<option[^>]*value=\"([^\"]*)\"[^>]*selected",
            RegexOption.DOT_MATCHES_ALL,
        )
        return regex.find(html)?.groupValues?.get(1).orEmpty()
    }
}
