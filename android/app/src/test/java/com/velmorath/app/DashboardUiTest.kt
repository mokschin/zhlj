package com.velmorath.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.card.MaterialCardView
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardUiTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText())
    private fun states(): Map<CampusSection, FeedState> = mapOf(
        CampusSection.NOTICES to FeedState(CampusFeed(FeedParser.notices(fixture("zhlj_notice.json")))),
        CampusSection.MESSAGES to FeedState(CampusFeed(FeedParser.messages(fixture("zhlj_messages.json")).mapIndexed { index, item ->
            val detail = AssignmentParser.fromNotice(item, fixture("chaoxing_assignment_notice.json").getJSONObject("data"))
            if (index == 0) detail.copy(submission = Submission.SUBMITTED) else detail.copy(startsAt = "2026-10-29 08:00", dueAt = "2026-11-02 23:59")
        })),
        CampusSection.COURSES to FeedState(CampusFeed(FeedParser.courses(fixture("zhlj_curriculum.json")), "第 9 周", "2026-11-02")),
        CampusSection.EXAMS to FeedState(CampusFeed(listOf(CampusItem("示例课程A", "2027-01-10 09:00–11:00", "1区101")))))
    @Test fun populatedPagesRenderInBothThemesAndNoticeDetailOpensOriginal() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("appearance", Context.MODE_PRIVATE)
        for ((theme, mode) in listOf("day" to AppCompatDelegate.MODE_NIGHT_NO, "night" to AppCompatDelegate.MODE_NIGHT_YES)) {
            prefs.edit().putInt("mode", mode).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
            model.courseDate = LocalDate.parse("2026-11-02")
            model.courseMonth = java.time.YearMonth.from(model.courseDate)
            model.noticeFilter = NoticeFilter(LocalDate.parse("2026-10-08"), LocalDate.parse("2026-11-06"))
            val renderer = DashboardRenderer(activity, model, {}, {}, { activity.showItem(it) }, {})
            val states = states()
            for (section in CampusSection.entries) {
                activity.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = when(section) {
                    CampusSection.NOTICES -> R.id.navNotices
                    CampusSection.MESSAGES -> R.id.navMessages
                    CampusSection.COURSES -> R.id.navCourses
                    CampusSection.EXAMS -> R.id.navExams
                }
                model.settingsSelected = false; model.upcomingSelected = false; model.selected = section
                if (section == CampusSection.COURSES) model.personalEvents = listOf(PersonalEvent(name = "小组讨论", tag = "学习", start = model.courseDate.atTime(17, 0), end = model.courseDate.atTime(18, 0), place = "图书馆 201", color = android.graphics.Color.rgb(0, 145, 170)))
                renderer.render(states)
                assertTrue(activity.findViewById<LinearLayout>(R.id.feedContent).childCount > 0)
                capture(activity.findViewById(R.id.root), "$theme-${section.name.lowercase()}")
                if (section == CampusSection.NOTICES) {
                    val favorite = states.getValue(section).feed!!.items.first()
                    NoticeFavorites(activity).toggle(favorite); model.localRevision++; renderer.render(states)
                    capture(activity.findViewById(R.id.root), "$theme-notices-favorite")
                    NoticeFavorites(activity).toggle(favorite); model.localRevision++; renderer.render(states)
                }
                if (section == CampusSection.MESSAGES) {
                    val content = activity.findViewById<LinearLayout>(R.id.feedContent)
                    (content.getChildAt(0) as MaterialCardView).performClick()
                    val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                    val labels = labels(dialog.window!!.decorView)
                    assertTrue(labels.any { it.startsWith("发布时间：") })
                    assertTrue(labels.any { it.startsWith("开始时间：2026-11-05") })
                    assertTrue(labels.any { it.startsWith("结束时间：2026-11-10") })
                    assertTrue(labels.any { it == "提交状态：已提交" })
                    assertNotNull(findText(dialog.window!!.decorView, "查看作业"))
                    dialog.dismiss()
                }
                if (section == CampusSection.COURSES) {
                    (findText(activity.findViewById(R.id.feedContent), "示例课程A")!!.parent as View).performClick()
                    val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                    val labels = labels(dialog.window!!.decorView)
                    assertTrue(labels.contains("地点：1区101"))
                    assertTrue(labels.contains("教师：张老师"))
                    dialog.dismiss()
                    model.courseWeekView = true; renderer.render(states)
                    capture(activity.findViewById(R.id.root), "$theme-week")
                    assertNull(findText(activity.findViewById(R.id.feedContent), "小组讨论"))
                    model.courseWeekView = false
                }
            }
            activity.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = R.id.navUpcoming
            model.settingsSelected = false; model.upcomingSelected = true
            val now = java.time.LocalDateTime.now(CourseTimes.zone)
            model.personalEvents = listOf(PersonalEvent(name = "示例讨论会", tag = "小组", start = now.plusHours(1), end = now.plusHours(2), place = "示例会议室", color = StableColors.personal(activity, "preview")))
            val period = CourseTimes.ends.indexOfFirst { java.time.LocalTime.parse(it) > now.toLocalTime() }.let { if (it < 0) 1 else it + 1 }
            val courseDate = if (now.toLocalTime() > java.time.LocalTime.parse(CourseTimes.ends.last())) now.toLocalDate().plusDays(1) else now.toLocalDate()
            val preview = states + (CampusSection.EXAMS to FeedState(CampusFeed(listOf(CampusItem("示例考试", "${FeedParser.today().plusDays(1)} · 14:00–16:00", "示例考场"))))) +
                (CampusSection.COURSES to FeedState(CampusFeed(listOf(CampusItem("示例课程", course = CourseSlot(courseDate.dayOfWeek.value, period, period, "示例教室", "教师"))), weekStart = courseDate.with(java.time.DayOfWeek.MONDAY).toString())))
            renderer.render(preview)
            capture(activity.findViewById(R.id.root), "$theme-upcoming")
            model.personalEvents = emptyList()
            model.upcomingSelected = false
            activity.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = R.id.navSettings
            ScheduleStore(activity).rememberSources(states.getValue(CampusSection.NOTICES).feed!!.items)
            model.settingsSelected = true; renderer.render(states)
            assertNotNull(activity.findViewById<View>(R.id.accounts))
            assertNotNull(activity.findViewById<View>(R.id.appearance))
            capture(activity.findViewById(R.id.root), "$theme-settings")
            listOf(SettingsPage.NOTICES, SettingsPage.PUSH, SettingsPage.REMINDERS, SettingsPage.DISPLAY).forEach { page ->
                model.settingsPage = page; renderer.render(states)
                capture(activity.findViewById(R.id.root), "$theme-settings-${page.name.lowercase()}")
            }
            model.settingsPage = SettingsPage.ROOT; renderer.render(states)
            if (theme == "day") {
                val icon = activity.getDrawable(R.mipmap.ic_launcher)!!
                val bitmap = Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888)
                icon.setBounds(0, 0, 216, 216); icon.draw(Canvas(bitmap))
                File("build/outputs/ui-preview/icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            model.settingsSelected = false; model.selected = CampusSection.NOTICES; renderer.render(states)
            val list = activity.findViewById<LinearLayout>(R.id.feedContent)
            (0 until list.childCount).map { list.getChildAt(it) }.filterIsInstance<MaterialCardView>().first().performClick()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            val labels = labels(dialog.window!!.decorView)
            assertTrue(labels.any { it.startsWith("来源：") })
            assertTrue(labels.any { it.contains("主题团日") })
            findText(dialog.window!!.decorView, "打开原文")!!.performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.webContainer).visibility)
            val web = activity.findViewById<WebView>(R.id.webView)
            assertTrue(shadowOf(web).lastLoadedUrl.contains("example.com"))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.loginHint).visibility)
            controller.pause().stop().destroy()
        }
        prefs.edit().clear().commit()
    }
    @Test fun backgroundWorkDoesNotSpinOrRebuildVisibleNoticeFeed() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
        model.upcomingSelected = false
        model.selected = CampusSection.NOTICES
        model.noticeFilter = NoticeFilter(LocalDate.parse("2026-10-08"), LocalDate.parse("2026-11-06"))
        val renderer = DashboardRenderer(activity, model, {}, {}, {}, {})
        val states = states()
        renderer.render(states)
        val first = activity.findViewById<LinearLayout>(R.id.feedContent).getChildAt(0)
        assertTrue(labels(first).any { it.contains("2026-11-05") })
        renderer.render(states + (CampusSection.MESSAGES to states.getValue(CampusSection.MESSAGES).copy(detailsLoading = true)))
        assertSame(first, activity.findViewById<LinearLayout>(R.id.feedContent).getChildAt(0))
        assertFalse(activity.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefresh).isRefreshing)
        assertTrue(activity.findViewById<View>(R.id.refresh).isEnabled)
        controller.pause().stop().destroy()
    }
    @Test fun weekFitsNarrowScreenAndViewSwitchIsOneTap() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
        model.selected = CampusSection.COURSES; model.upcomingSelected = false
        model.courseDate = LocalDate.parse("2026-11-02"); model.courseMonth = java.time.YearMonth.from(model.courseDate)
        val renderer = DashboardRenderer(activity, model, {}, {}, {}, {})
        renderer.render(states())
        fun findDescription(view: View, value: String): View? {
            if (view.contentDescription == value) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findDescription(view.getChildAt(index), value)?.let { return it }
            return null
        }
        findDescription(activity.findViewById(R.id.feedControls), "切换周表")!!.performClick()
        assertTrue(model.courseWeekView)
        findDescription(activity.findViewById(R.id.feedControls), "切换月历")!!.performClick()
        assertFalse(model.courseWeekView)
        assertFalse(labels(activity.findViewById(R.id.feedControls)).any { it.contains("收起") || it.contains("展开") })
        val week = TimetableView(activity, states().getValue(CampusSection.COURSES).feed!!) {}
        val width = (288 * activity.resources.displayMetrics.density).toInt()
        week.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        week.layout(0, 0, width, week.measuredHeight)
        val table = week.getChildAt(0) as ViewGroup
        assertTrue(table.width <= width)
        for (index in 0 until table.childCount) assertTrue(table.getChildAt(index).right <= width)
        controller.pause().stop().destroy()
    }
    private fun capture(root: View, name: String) {
        val width = 786; val height = 1704
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("build/outputs/ui-preview/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun labels(view: View): List<String> = if (view is TextView) listOf(view.text.toString()) else if (view is ViewGroup)
        (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()
    private fun findText(view: View, text: String): View? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), text)?.let { return it }
        return null
    }
}
