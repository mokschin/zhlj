package com.velmorath.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var renderer: DashboardRenderer
    private lateinit var model: DashboardViewModel
    private lateinit var webView: WebView
    private lateinit var webContainer: View
    private val portalUrl = "https://zhlj.whu.edu.cn/pc/index"
    private val jwglUrl = "https://jwgl.whu.edu.cn/"
    private var loginTarget: String? = null
    private var loginCheck: Job? = null
    private var clearDetailHistory = false
    private var openAssignmentId: String? = null
    private var lastScheduleSnapshot: Any? = null
    private var lastNotices: CampusFeed? = null
    private val scheduleTick = object : Runnable {
        override fun run() {
            if (::renderer.isInitialized && ::model.isInitialized && model.upcomingSelected && !model.settingsSelected) renderer.render(model.states.value)
            window.decorView.postDelayed(this, 30000)
        }
    }
    private val scrollPositions = mutableMapOf<String, Int>()
    internal var loginVerifier: suspend (String, String) -> Boolean = ZhljApi::isAuthenticated

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = getSharedPreferences("appearance", MODE_PRIVATE)
            .getInt("mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        model = ViewModelProvider(this)[DashboardViewModel::class.java]
        val store = ScheduleStore(this)
        if (model.states.value.values.all { it == FeedState() }) model.restoreSchedule(store.feeds(), store.exams(), store.personal())
        model.personalEvents = store.personal()
        val messagePrefs = Reminders.prefs(this)
        model.noticeFilter = NoticeFilter(java.time.LocalDate.MIN, java.time.LocalDate.MAX, if (messagePrefs.contains("filter_sources")) messagePrefs.getStringSet("filter_sources", emptySet())?.toSet() else messagePrefs.getString("filter_source", null)?.let { setOf(it) }, messagePrefs.getBoolean("newest", true))
        model.onDataChanged = { persistSchedule() }
        model.enabledSections = CampusSection.entries.filter { sectionEnabled(it.name) }.toSet()
        if (sectionEnabled("upcoming")) model.enabledSections = model.enabledSections + setOf(CampusSection.COURSES, CampusSection.EXAMS)
        model.upcomingSelected = savedInstanceState?.getBoolean("upcomingSelected") ?: (!model.initialRequested && sectionEnabled("upcoming"))
        savedInstanceState?.getString("section")?.let { value ->
            CampusSection.entries.find { it.name == value }?.let { model.selected = it }
        }
        model.settingsSelected = savedInstanceState?.getBoolean("settingsSelected") ?: model.settingsSelected
        renderer = DashboardRenderer(this, model,
            onAccount = { jwgl -> if (jwgl) openLogin(jwglUrl, "教务登录") else openLogin(portalUrl, "门户登录") },
            onAppearance = { showAppearance() }, onItem = { showItem(it) }, onRefresh = { if (model.settingsSelected) refreshAll() else refreshCurrent() },
            onCourseDate = { date -> model.selectCourseDate(date, CookieManager.getInstance().getCookie(portalUrl).orEmpty()) })
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        webView = findViewById(R.id.webView)
        webContainer = findViewById(R.id.webContainer)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                findViewById<View>(R.id.webProgress).visibility = if (newProgress < 100 && webContainer.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) Toast.makeText(this@MainActivity, "网页加载失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme in listOf("https", "http")) return false
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)) }
                catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this@MainActivity, "未找到可打开此链接的应用", Toast.LENGTH_SHORT).show() }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (clearDetailHistory && loginTarget == null) {
                    view.clearHistory(); clearDetailHistory = false
                }
                checkLogin(url)
                openAssignmentId?.let { id ->
                    if (AssignmentParser.isWorkUrl(url)) listOf(0L, 1500L, 4000L, 8000L).forEach { delay ->
                        view.postDelayed({
                            if (!isDestroyed && openAssignmentId == id && view.url == url && webContainer.visibility == View.VISIBLE) {
                                view.evaluateJavascript("document.documentElement.outerHTML") { value ->
                                    if (openAssignmentId == id) {
                                        val html = try { org.json.JSONTokener(value).nextValue() as? String } catch (_: Exception) { null }
                                        if (html != null) model.assignmentPage(id, html)
                                    }
                                }
                            }
                        }, delay)
                    }
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        findViewById<MaterialButton>(R.id.closeWeb).setOnClickListener { closeLogin() }
        findViewById<View>(R.id.refresh).setOnClickListener { refreshCurrent() }
        findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).apply {
            setColorSchemeColors(ContextCompat.getColor(this@MainActivity, R.color.accent))
            setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this@MainActivity, R.color.card))
            setOnChildScrollUpCallback { _, _ -> findViewById<ScrollView>(R.id.feedScroll).canScrollVertically(-1) }
            setOnRefreshListener { refreshCurrent(); isRefreshing = model.states.value.getValue(model.selected).loading }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webContainer.visibility == View.VISIBLE) {
                    if (loginTarget == null && webView.canGoBack()) webView.goBack() else closeLogin()
                }
                else if (model.settingsSelected && model.settingsPage != SettingsPage.ROOT) openSettingsPage(model.settingsPage.parent)
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        findViewById<BottomNavigationView>(R.id.navigation).apply {
            isItemActiveIndicatorEnabled = false
            background = android.graphics.drawable.ColorDrawable(ContextCompat.getColor(this@MainActivity, R.color.card))
            updateNavigation()
            selectedItemId = if (model.upcomingSelected) R.id.navUpcoming else if (model.settingsSelected) R.id.navSettings else menuId(model.selected)
            setOnItemReselectedListener { }
            setOnItemSelectedListener { item ->
                val scroll = this@MainActivity.findViewById<ScrollView>(R.id.feedScroll)
                scrollPositions[if (model.settingsSelected) "settings" else if (model.upcomingSelected) "upcoming" else model.selected.name] = scroll.scrollY
                if (!model.settingsSelected) model.settingsPage = SettingsPage.ROOT
                model.settingsSelected = item.itemId == R.id.navSettings
                model.upcomingSelected = item.itemId == R.id.navUpcoming
                if (!model.settingsSelected) model.selected = when (item.itemId) {
                    R.id.navMessages -> CampusSection.MESSAGES
                    R.id.navUpcoming, R.id.navCourses -> CampusSection.COURSES
                    R.id.navExams -> CampusSection.EXAMS
                    else -> CampusSection.NOTICES
                }
                render(model.states.value)
                val position = scrollPositions[if (model.settingsSelected) "settings" else if (model.upcomingSelected) "upcoming" else model.selected.name] ?: 0
                scroll.post { scroll.scrollTo(0, position) }
                true
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.states.collect { render(it) }
            }
        }
        if (savedInstanceState?.getBoolean("loginOpen") == true) {
            webContainer.visibility = View.VISIBLE
            loginTarget = savedInstanceState.getString("loginTarget")
            openAssignmentId = savedInstanceState.getString("openAssignmentId")
            findViewById<TextView>(R.id.loginTitle).text = savedInstanceState.getString("loginTitle")
            findViewById<View>(R.id.loginHint).visibility = if (loginTarget != null) View.VISIBLE else View.GONE
            findViewById<MaterialButton>(R.id.closeWeb).text = if (loginTarget != null) "完成" else "返回"
            webView.restoreState(savedInstanceState)
        } else if (!model.initialRequested) { model.initialRequested = true; refreshAll() }
        if (intent.getBooleanExtra("open_notices", false)) findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = R.id.navNotices
        persistSchedule()
    }

    private fun persistSchedule() {
        val feeds = model.allCourseFeeds()
        feeds.flatMap { it.items }.map { it.title }.distinct().sorted().forEach { CoursePalette.colors(this, it) }
        val exams = model.states.value.getValue(CampusSection.EXAMS).feed
        val snapshot = listOf(feeds, exams, model.personalEvents)
        if (snapshot != lastScheduleSnapshot) {
            lastScheduleSnapshot = snapshot
            ScheduleStore(this).save(feeds, exams)
            Reminders.schedule(this)
        }
        val notices = model.states.value.getValue(CampusSection.NOTICES).feed
        if (notices != null && notices != lastNotices) {
            lastNotices = notices
            ScheduleStore(this).rememberSources(notices.items)
            if (Reminders.prefs(this).getBoolean("notice", false)) Reminders.processNotices(this, notices.items, !notices.partial)
        }
    }
    internal fun editSchedule(existing: PersonalEvent? = null, date: java.time.LocalDate = FeedParser.today()) {
        persistSchedule()
        ScheduleEditor(this) { value, deleted ->
            model.personalEvents = model.personalEvents.filter { it.id != (deleted ?: value?.id) } + listOfNotNull(value)
            ScheduleStore(this).savePersonal(model.personalEvents)
            model.localRevision++; persistSchedule(); render(model.states.value)
        }.show(existing, date)
    }
    internal fun openMessageSettings() {
        findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = R.id.navSettings
        openSettingsPage(SettingsPage.NOTICES)
    }
    internal fun openSettingsPage(page: SettingsPage) {
        model.settingsPage = page
        render(model.states.value)
        findViewById<ScrollView>(R.id.feedScroll).scrollTo(0, 0)
    }
    internal fun importCalendar() = CalendarImport(this, model).show()
    internal fun requestNotificationAccess(openSettings: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !Reminders.permitted(this)) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 601)
        else if (openSettings || !androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 602 && grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) importCalendar()
        if (requestCode == 601) { Reminders.schedule(this); model.localRevision++; render(model.states.value) }
    }
    override fun onResume() {
        super.onResume()
        window.decorView.removeCallbacks(scheduleTick); window.decorView.post(scheduleTick)
        if (::model.isInitialized) { model.localRevision++; render(model.states.value) }
    }
    override fun onStop() { window.decorView.removeCallbacks(scheduleTick); super.onStop() }

    internal fun sectionOptions() = listOf("upcoming" to "接下来", "NOTICES" to "通知", "COURSES" to "日程", "EXAMS" to "考试", "MESSAGES" to "作业")
    internal fun sectionEnabled(key: String) = getSharedPreferences("sections", MODE_PRIVATE).getBoolean(key, key != "MESSAGES")
    internal fun setSectionEnabled(key: String, enabled: Boolean) {
        getSharedPreferences("sections", MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
        model.enabledSections = CampusSection.entries.filter { sectionEnabled(it.name) }.toSet()
        if (sectionEnabled("upcoming")) model.enabledSections = model.enabledSections + setOf(CampusSection.COURSES, CampusSection.EXAMS)
        updateNavigation()
    }
    private fun updateNavigation() {
        val navigation = findViewById<BottomNavigationView>(R.id.navigation)
        navigation.menu.findItem(R.id.navUpcoming).isVisible = sectionEnabled("upcoming")
        CampusSection.entries.forEach { navigation.menu.findItem(menuId(it)).isVisible = sectionEnabled(it.name) }
        if (!model.settingsSelected && !model.upcomingSelected && !sectionEnabled(model.selected.name)) {
            model.settingsSelected = true; navigation.selectedItemId = R.id.navSettings
        }
    }
    private fun menuId(section: CampusSection) = when (section) {
        CampusSection.NOTICES -> R.id.navNotices
        CampusSection.MESSAGES -> R.id.navMessages
        CampusSection.COURSES -> R.id.navCourses
        CampusSection.EXAMS -> R.id.navExams
    }
    private fun openLogin(url: String, title: String) {
        loginCheck?.cancel()
        loginTarget = if (url == portalUrl) "portal" else "jwgl"
        openAssignmentId = null
        clearDetailHistory = false
        findViewById<TextView>(R.id.loginTitle).text = title
        findViewById<View>(R.id.loginHint).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.closeWeb).text = "完成"
        webContainer.visibility = View.VISIBLE
        webView.loadUrl(url)
    }
    private fun closeLogin(authenticated: Boolean = false) {
        loginCheck?.cancel()
        val wasLogin = loginTarget != null
        loginTarget = null
        openAssignmentId = null
        clearDetailHistory = false
        webView.stopLoading()
        CookieManager.getInstance().flush()
        webContainer.visibility = View.GONE
        findViewById<View>(R.id.webProgress).visibility = View.GONE
        if (wasLogin && authenticated) refreshAll(queueIfBusy = true)
    }
    private fun checkLogin(url: String) {
        val target = loginTarget ?: return
        if (!LoginDestination.isCampusPage(target, url) || loginCheck?.isActive == true) return
        loginCheck = lifecycleScope.launch {
            val cookie = CookieManager.getInstance().getCookie(if (target == "portal") portalUrl else jwglUrl).orEmpty()
            val authenticated = try {
                loginVerifier(target, cookie)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            if (authenticated && loginTarget == target && webContainer.visibility == View.VISIBLE) {
                loginCheck = null
                closeLogin(authenticated = true)
            }
        }
    }
    private fun showAppearance() {
        val modes = intArrayOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
        val prefs = getSharedPreferences("appearance", MODE_PRIVATE)
        MaterialAlertDialogBuilder(this).setTitle("外观")
            .setSingleChoiceItems(arrayOf("跟随系统", "日间", "夜间"),
                modes.indexOf(prefs.getInt("mode", modes[0]))) { dialog, index ->
                prefs.edit().putInt("mode", modes[index]).apply()
                dialog.dismiss()
                delegate.localNightMode = modes[index]
            }.setNegativeButton("取消", null).show()
    }
    private fun refreshCurrent() {
        model.refresh(CookieManager.getInstance().getCookie(portalUrl).orEmpty(),
            CookieManager.getInstance().getCookie(jwglUrl).orEmpty(), sections = if (model.upcomingSelected) setOf(CampusSection.COURSES, CampusSection.EXAMS) else setOf(model.selected))
    }
    private fun refreshAll(queueIfBusy: Boolean = false) {
        model.refresh(CookieManager.getInstance().getCookie(portalUrl).orEmpty(),
            CookieManager.getInstance().getCookie(jwglUrl).orEmpty(), queueIfBusy)
    }
    private fun render(states: Map<CampusSection, FeedState>) = renderer.render(states)

    internal fun showItem(item: CampusItem) {
        if (item.personal != null) { editSchedule(item.personal); return }
        val dialog = BottomSheetDialog(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        body.addView(label(item.title, 20f, R.color.ink).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        fun line(value: String) { body.addView(label(value, 16f, R.color.muted).apply { setPadding(0, dp(12), 0, 0) }) }
        fun group(title: String) {
            body.addView(label(title, 14f, R.color.accent).apply { setPadding(0, dp(20), 0, dp(2)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        }
        item.course?.let { slot ->
            group("课程安排")
            line("周${"一二三四五六日"[slot.day - 1]}  ${CourseTimes.label(slot)}")
            line("地点：${slot.room.ifBlank { "未提供" }}")
            line("教师：${slot.teacher.ifBlank { "未提供" }}")
            if (slot.weeks.isNotBlank()) line(slot.weeks)
        } ?: run {
            group("发布信息")
            if (item.source.isNotBlank()) line("来源：${item.source}")
            if (item.publishedAt.isNotBlank()) line("发布时间：${item.publishedAt}")
            if (item.source == "珞珈在线") {
                group("作业时间与状态")
                line("开始时间：${item.startsAt.ifBlank { "未获取到" }}")
                line("结束时间：${item.dueAt.ifBlank { "未获取到" }}")
                line("提交状态：${item.submission.label}")
                AssignmentParser.availability(item).takeIf { it.isNotBlank() }?.let { line("开放状态：$it") }
            }
            if (item.subtitle.isNotBlank()) line(item.subtitle)
            if (item.detail.isNotBlank()) line(item.detail)
        }
        if (item.content.isNotBlank()) group("正文")
        if (item.content.isNotBlank()) body.addView(label(item.content, 16f, R.color.ink).apply {
            setPadding(0, dp(24), 0, dp(16)); setTextIsSelectable(true)
        })
        val ui = UiKit(this)
        if (item.url.isNotBlank()) body.addView(ui.primary(if (item.workUrl.isNotBlank()) "查看作业" else "打开原文") { dialog.dismiss(); openDetail(item) })
        body.addView(ui.action("关闭") { dialog.dismiss() })
        dialog.setContentView(ScrollView(this).apply { addView(body) })
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }
    private fun openDetail(item: CampusItem) {
        val url = FeedParser.webUrl(item.workUrl.ifBlank { item.url })
        if (url.isBlank()) return
        loginCheck?.cancel(); loginTarget = null
        openAssignmentId = item.takeIf { it.source == "珞珈在线" }?.id
        clearDetailHistory = true
        webView.clearHistory()
        findViewById<TextView>(R.id.loginTitle).text = item.title
        findViewById<View>(R.id.loginHint).visibility = View.GONE
        findViewById<MaterialButton>(R.id.closeWeb).text = "返回"
        webContainer.visibility = View.VISIBLE
        webView.loadUrl(url)
    }
    private fun label(value: String, size: Float, colorId: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(ContextCompat.getColor(this@MainActivity, colorId))
        setLineSpacing(dp(4).toFloat(), 1f)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("settingsSelected", model.settingsSelected)
        outState.putBoolean("upcomingSelected", model.upcomingSelected)
        outState.putString("section", model.selected.name)
        outState.putBoolean("loginOpen", webContainer.visibility == View.VISIBLE)
        outState.putString("loginTitle", findViewById<TextView>(R.id.loginTitle).text.toString())
        outState.putString("loginTarget", loginTarget)
        outState.putString("openAssignmentId", openAssignmentId)
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        model.onDataChanged = null
        window.decorView.removeCallbacks(scheduleTick)
        loginCheck?.cancel()
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
