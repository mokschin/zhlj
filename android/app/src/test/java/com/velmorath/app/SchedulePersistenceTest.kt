package com.velmorath.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SchedulePersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun clear() { listOf("stable_colors", "schedule_data", "messages").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() } }
    @After fun cleanup() = clear()
    @Test fun colorsDoNotCollideBeyondOriginalSixAndRemainStable() {
        val assigned = (1..24).associate { "课$it" to StableColors.get(context, "course", "课$it") }
        assertEquals(24, assigned.values.toSet().size)
        assigned.toList().reversed().forEach { (key, value) -> assertEquals(value, StableColors.get(context, "course", key)) }
        val custom = StableColors.personal(context, "event")
        assertTrue(custom !in assigned.values)
        assertEquals(custom, StableColors.personal(context, "event"))
        assertEquals(StableColors.get(context, "source", "部门1"), StableColors.get(context, "source", "部门1"))
        assertNotEquals(StableColors.get(context, "source", "部门1"), StableColors.get(context, "source", "部门2"))
    }
    @Test fun personalAndCourseDataSurviveNewStoreAndDelete() {
        val store = ScheduleStore(context)
        val event = PersonalEvent(name = "项目讨论", tag = "工作", start = LocalDateTime.parse("2026-11-06T13:00"), end = LocalDateTime.parse("2026-11-06T14:00"), place = "会议室", color = 123)
        val feed = CampusFeed(listOf(CampusItem("课程", course = CourseSlot(1, 1, 2, "101", "老师"))), weekStart = "2026-11-02")
        store.savePersonal(listOf(event)); store.save(listOf(feed), null)
        assertEquals(event, ScheduleStore(context).personal().single())
        assertEquals(feed.items, ScheduleStore(context).feeds().single().items)
        store.savePersonal(emptyList()); assertTrue(ScheduleStore(context).personal().isEmpty())
    }
    @Test fun subscriptionBaselinesAndDeduplicatesOnlyChosenSources() {
        val p = Reminders.prefs(context)
        p.edit().putBoolean("notice", true).putStringSet("notice_sources", setOf("a")).commit()
        val original = CampusItem("历史", id = "old", sourceId = "a")
        Reminders.processNotices(context, listOf(original))
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertEquals(0, manager.allNotifications.size)
        val news = listOf(original, CampusItem("新消息", id = "new", sourceId = "a"), CampusItem("其他来源", id = "other", sourceId = "b"))
        Reminders.processNotices(context, news); Reminders.processNotices(context, news)
        assertEquals(1, manager.allNotifications.size)
    }
    @Test fun alarmChangesWhenLeadChangesAndCancelsWhenDisabled() {
        val now = LocalDateTime.now(CourseTimes.zone)
        val event = PersonalEvent(name = "会议", tag = "", start = now.plusHours(4), end = now.plusHours(5), place = "", color = 1)
        ScheduleStore(context).savePersonal(listOf(event))
        val p = Reminders.prefs(context); p.edit().putBoolean("personal", true).commit()
        Reminders.schedule(context)
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, manager.scheduledAlarms.size)
        val at = manager.scheduledAlarms.single().triggerAtTime
        p.edit().putInt("personal_minutes", 30).commit(); Reminders.schedule(context)
        assertEquals(1, manager.scheduledAlarms.size)
        assertEquals(at - 15 * 60 * 1000, manager.scheduledAlarms.single().triggerAtTime)
        p.edit().putBoolean("personal", false).commit(); Reminders.schedule(context)
        assertTrue(manager.scheduledAlarms.isEmpty())
    }
}
