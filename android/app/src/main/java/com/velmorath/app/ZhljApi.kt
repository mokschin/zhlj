package com.velmorath.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.TimeZone

class SessionExpiredException : Exception()

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
    private var cachedTerms: JSONObject? = null
    private var termsFetchedAt = 0L
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
            checkSessionRedirect(response.request.url.host, response.request.url.encodedPath)
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
            checkSessionRedirect(response.request.url.host, response.request.url.encodedPath)
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
            checkSessionRedirect(response.request.url.host, response.request.url.encodedPath)
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun checkSessionRedirect(host: String, path: String) {
        if (host == "cas.whu.edu.cn" || path.contains("login", ignoreCase = true)) {
            throw SessionExpiredException()
        }
    }

    suspend fun isAuthenticated(target: String, cookie: String): Boolean = withContext(Dispatchers.IO) {
        if (cookie.isBlank()) return@withContext false
        when (target) {
            "portal" -> JSONObject(get("https://zhlj.whu.edu.cn/whdxIndex/getMessage?type=1",
                cookie, PORTAL_REFERER)).optInt("result") == 1
            "jwgl" -> {
                val page = get("https://jwgl.whu.edu.cn/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105&layout=default",
                    cookie, "https://jwgl.whu.edu.cn/xtgl/index_initMenu.html")
                selectedOption(page, "cx_xnm").isNotBlank() && selectedOption(page, "cx_xqm").isNotBlank()
            }
            else -> false
        }
    }

    suspend fun notices(cookie: String): CampusFeed = withContext(Dispatchers.IO) {
        loadRecentNotices { start, count ->
            FeedParser.notices(JSONObject(postJson(
                "https://zhlj.whu.edu.cn/whdxIndex/getNotice",
                JSONObject().put("start", start).put("count", count), cookie, PORTAL_REFERER)))
        }
    }

    suspend fun messages(cookie: String): CampusFeed = withContext(Dispatchers.IO) {
        val payload = JSONObject(get("https://zhlj.whu.edu.cn/whdxIndex/getMessage?type=1", cookie, PORTAL_REFERER))
        CampusFeed(FeedParser.messages(payload), returnedCount = payload.optJSONObject("data")?.optInt("count") ?: 0)
    }

    suspend fun assignmentDetails(item: CampusItem): CampusItem = withContext(Dispatchers.IO) {
        val url = item.url.toHttpUrlOrNull() ?: return@withContext item
        if (!url.host.endsWith(".chaoxing.com") || url.scheme != "https") return@withContext item
        var updated = item
        if (url.pathSegments.size == 2 && url.pathSegments[0] == "share") {
            val endpoint = url.newBuilder().encodedPath("/share/notice/${url.pathSegments[1]}/notice_data")
                .query(null).addQueryParameter("pt", url.queryParameter("pt").orEmpty()).addQueryParameter("wxsn", "").build()
            val payload = JSONObject(assignmentGet(endpoint.toString(), item.url))
            if (payload.optInt("result") == 1) updated = AssignmentParser.fromNotice(item, payload.optJSONObject("data") ?: JSONObject())
        } else if (AssignmentParser.isWorkUrl(item.url)) updated = item.copy(workUrl = item.url)
        if (updated.workUrl.isNotBlank() && !android.webkit.CookieManager.getInstance().getCookie(updated.workUrl).isNullOrBlank()) {
            delay(3000)
            try { updated = AssignmentParser.fromHtml(updated, assignmentGet(updated.workUrl, item.url), personal = true) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* An expired personal login must not discard the notice's start/end times. */ }
        }
        updated
    }

    private fun assignmentGet(url: String, referer: String): String {
        val parsed = url.toHttpUrlOrNull() ?: error("Invalid assignment URL")
        require(parsed.scheme == "https" && parsed.host.endsWith(".chaoxing.com"))
        // A signed share URL is sufficient for its notice. Never forward the WHU portal Cookie.
        val request = Request.Builder().url(parsed).header("User-Agent", UA).header("Referer", referer)
        android.webkit.CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { request.header("Cookie", it) }
        client.newBuilder().followRedirects(false).build().newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) error("Assignment page unavailable")
            return response.body?.string().orEmpty()
        }
    }

    suspend fun courses(cookie: String, selectedDate: LocalDate? = null): CampusFeed = withContext(Dispatchers.IO) {
        val termPayload = cachedTerms?.takeIf { System.currentTimeMillis() - termsFetchedAt < 600_000 } ?: JSONObject(get(
            "https://zhlj.whu.edu.cn/whdxSchedule/getNearSchoolTerm", cookie, PORTAL_REFERER)).also {
                if (it.optInt("result") == 1) { cachedTerms = it; termsFetchedAt = System.currentTimeMillis() }
            }
        val terms = termPayload.optJSONArray("data") ?: JSONArray()
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
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai"); isLenient = false }
            .parse(current.optString("termStartTime"))
            ?: throw RuntimeException("学期开始日期无效")
        val target = selectedDate ?: FeedParser.today()
        val termStart = start.toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()
        val mondayAnchor = if (termStart.dayOfWeek == java.time.DayOfWeek.SUNDAY) termStart.plusDays(1) else termStart.with(java.time.DayOfWeek.MONDAY)
        val week = Math.floorDiv(ChronoUnit.DAYS.between(mondayAnchor, target.with(java.time.DayOfWeek.MONDAY)).toInt(), 7) + 1
        if (selectedDate != null && (week < 1 || week > current.optInt("termWeekCount", 30))) {
            return@withContext CampusFeed(emptyList(), "非当前学期", target.with(java.time.DayOfWeek.MONDAY).toString())
        }
        val boundedWeek = week.coerceIn(1, current.optInt("termWeekCount", week).coerceAtLeast(1))
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            time = start
            add(Calendar.DAY_OF_YEAR, (boundedWeek - 1) * 7)
            if (get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) add(Calendar.DAY_OF_YEAR, 1)
        }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(calendar.time)
        delay(3000)
        val text = get(
            "https://zhlj.whu.edu.cn/whdxIndex/getCurriculum?date=$date",
            cookie,
            PORTAL_REFERER,
        )
        CampusFeed(FeedParser.courses(JSONObject(text)), "第 $boundedWeek 周", weekStart = date)
    }

    suspend fun exams(cookie: String): CampusFeed = withContext(Dispatchers.IO) {
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
        delay(3000)
        val text = postForm(
            "https://jwgl.whu.edu.cn/kwgl/kscx_cxXsksxxIndex.html?doType=query",
            form,
            cookie,
            pageUrl,
        )
        val payload = JSONObject(text)
        val items = payload.optJSONArray("items")
            ?: throw RuntimeException("考试接口返回异常，请重新登录教务")
        CampusFeed((0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            CampusItem(item.clean("kcmc"),
                listOf(item.clean("ksrq"), item.clean("kssj")).filter { it.isNotBlank() }.joinToString(" · "),
                item.clean("cdmc"))
        })
    }
    private fun JSONObject.clean(key: String): String = if (isNull(key)) "" else optString(key).trim()

    internal fun selectedOption(html: String, id: String): String {
        val select = Jsoup.parse(html).getElementById(id) ?: return ""
        val option = select.selectFirst("option[selected]") ?: select.selectFirst("option")
        return option?.attr("value").orEmpty()
    }
}
