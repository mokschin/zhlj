package com.velmorath.app

import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.ViewModelProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-xhdpi")
class NoticeFavoritesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before @After fun clear() { context.getSharedPreferences("notice_favorites", 0).edit().clear().commit() }
    @Test fun snapshotsSurviveReloadAndSameIdFromOtherSourcesIsDistinct() {
        val item = CampusItem("通知", id = "1", sourceId = "a", source = "学院", publishedAt = "2026-10-01", url = "https://example.com/notice", content = "正文")
        assertTrue(NoticeFavorites(context).toggle(item))
        assertEquals(listOf(item), NoticeFavorites(context).items())
        val other = item.copy(sourceId = "b")
        assertFalse(NoticeFavorites(context).contains(other))
        NoticeFavorites(context).toggle(other)
        assertFalse(NoticeFavorites(context).toggle(item.copy(title = "更新标题")))
        assertEquals(listOf(other), NoticeFavorites(context).items())
    }
    @Test fun swipeRightTogglesButTapVerticalLeftAndCancelledGesturesDoNot() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        var toggles = 0; var opens = 0
        val card = NoticeCard(activity, CampusItem("通知"), false, { opens++ }) { toggles++ }
        activity.findViewById<LinearLayout>(R.id.feedContent).addView(card)
        fun gesture(dx: Float, dy: Float, cancel: Boolean = false) {
            card.translationX = 0f
            listOf(Triple(MotionEvent.ACTION_DOWN, 20f, 20f), Triple(MotionEvent.ACTION_MOVE, 20f + dx, 20f + dy), Triple(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 20f + dx, 20f + dy)).forEach { (action, x, y) ->
                val event = MotionEvent.obtain(0, 20, action, x, y, 0); card.onTouchEvent(event); event.recycle()
            }
        }
        gesture(180f, 2f); assertEquals(1, toggles); assertEquals(0, opens)
        gesture(2f, 180f); gesture(-180f, 0f); gesture(180f, 0f, true); gesture(60f, 0f)
        assertEquals(1, toggles); assertEquals(0, opens)
        gesture(0f, 0f); assertEquals(1, opens)
        val item = CampusItem("已收藏", sourceId = "a")
        val saved = NoticeCard(activity, item, true, {}) {}
        assertEquals(StableColors.get(activity, "source", "a"), saved.cardBackgroundColor.defaultColor)
        controller.pause().stop().destroy()
    }
    @Test fun favoritesButtonShowsSavedNoticesWithoutLiveFeedAndIgnoresSourceFilter() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val model = ViewModelProvider(activity)[DashboardViewModel::class.java]
        NoticeFavorites(activity).toggle(CampusItem("旧收藏", sourceId = "saved", publishedAt = "2020-01-01"))
        model.noticeFilter = NoticeFilter(sourceIds = setOf("other"))
        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.navigation).selectedItemId = R.id.navNotices
        activity.findViewById<View>(R.id.favoritesAction).performClick()
        assertTrue(model.favoritesOnly)
        val content = activity.findViewById<LinearLayout>(R.id.feedContent)
        assertTrue((0 until content.childCount).any { content.getChildAt(it) is NoticeCard })
        activity.findViewById<View>(R.id.favoritesAction).performClick(); assertFalse(model.favoritesOnly)
        controller.pause().stop().destroy()
    }
}
