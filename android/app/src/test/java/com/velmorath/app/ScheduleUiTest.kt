package com.velmorath.app

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-xhdpi")
class ScheduleUiTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    @After fun clean() {
        val context = RuntimeEnvironment.getApplication()
        listOf("schedule_data", "stable_colors", "messages").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }
    @Test fun editorValidatesNameAndPreservesAllCustomFields() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val start = LocalDateTime.now(CourseTimes.zone).plusHours(2).withSecond(0).withNano(0)
        val original = PersonalEvent(name = "", tag = "讨论", start = start, end = start.plusHours(1), place = "会议室", color = 123)
        var saved: PersonalEvent? = null
        ScheduleEditor(activity) { value, _ -> saved = value }.show(original)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(-1).performClick(); assertNull(saved)
        val name = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().first()
        name.setText("组会")
        dialog.getButton(-1).performClick()
        assertEquals(original.copy(name = "组会"), saved)
        controller.pause().stop().destroy()
    }
    @Test fun ongoingCourseShowsEndCountdownAndNoCalendarDate() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val now = LocalDateTime.now(CourseTimes.zone)
        val item = CampusItem("进行中的课", course = CourseSlot(now.dayOfWeek.value, 1, 2, "101", ""))
        val event = UpcomingEvent(item, now.toLocalDate(), now.minusMinutes(10), now.plusMinutes(30), "08:00–09:35", "101", false)
        val card = UpcomingCard(activity, event) {}
        activity.findViewById<LinearLayout>(R.id.feedContent).addView(card)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val labels = descendants(card).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(labels.contains("距离下课"))
        assertTrue(labels.contains("08:00–09:35"))
        assertFalse(labels.any { it.contains("今天") || it.contains("明天") })
        controller.pause().stop().destroy()
    }
    @Test fun noticeSourcesAllowMultipleSelectionsAndPersist() {
        val context = RuntimeEnvironment.getApplication()
        Reminders.prefs(context).edit().putString("filter_source", "a").commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
        assertEquals(setOf("a"), model.noticeFilter.sourceIds)
        val items = listOf(CampusItem("A", sourceId = "a", source = "教务"), CampusItem("B", sourceId = "b", source = "学院"), CampusItem("C", sourceId = "c", source = "其他"))
        ScheduleStore(activity).rememberSources(items)
        activity.openMessageSettings()
        fun check(name: String) = descendants(activity.findViewById(R.id.feedContent)).filterIsInstance<com.google.android.material.checkbox.MaterialCheckBox>().first { it.contentDescription == name }
        check("学院").performClick()
        assertEquals(setOf("a", "b"), model.noticeFilter.sourceIds)
        assertEquals(listOf("A", "B"), model.noticeFilter.apply(items).map { it.title })
        assertEquals(setOf("a", "b"), Reminders.prefs(activity).getStringSet("filter_sources", emptySet()))
        check("教务").performClick(); check("学院").performClick()
        assertTrue(model.noticeFilter.apply(items).isEmpty())
        check("全部来源").performClick(); assertEquals(3, model.noticeFilter.apply(items).size)
        activity.onBackPressedDispatcher.onBackPressed(); assertEquals(SettingsPage.ROOT, model.settingsPage)
        controller.pause().stop().destroy()
    }
    @Test fun calendarDotsFitInsideRowAndNotificationFiltersMoveToSettings() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val date = LocalDate.of(2026, 11, 6)
        val calendar = CourseCalendarView(activity, YearMonth.from(date), date, { (1..6).map { CampusItem("课程$it") } }, {}, {})
        calendar.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        calendar.layout(0, 0, 700, calendar.measuredHeight)
        val cell = descendants(calendar).first { it.contentDescription == date.toString() } as ViewGroup
        val dots = cell.getChildAt(1) as ViewGroup
        assertTrue(dots.bottom <= cell.height)
        for (index in 0 until dots.childCount) assertTrue(dots.getChildAt(index).bottom <= dots.height)
        val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
        model.upcomingSelected = false; model.settingsSelected = false; model.selected = CampusSection.NOTICES
        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.navigation).selectedItemId = R.id.navNotices
        assertEquals(0, activity.findViewById<LinearLayout>(R.id.feedControls).childCount)
        activity.findViewById<View>(R.id.secondaryAction).performClick()
        assertTrue(model.settingsSelected)
        assertEquals(SettingsPage.NOTICES, model.settingsPage)
        activity.findViewById<View>(R.id.settingsBack).performClick()
        assertEquals(SettingsPage.ROOT, model.settingsPage)
        controller.pause().stop().destroy()
    }
}
