package com.velmorath.app

import android.widget.TextView
import android.view.View
import android.webkit.WebView
import android.os.Looper
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainActivityTest {
    @After fun resetPreferences() {
        RuntimeEnvironment.getApplication().getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .edit().clear().commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("sections", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun sectionVisibilityPersistsAndAssignmentsDefaultOff() {
        var controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var activity = controller.get()
        var navigation = activity.findViewById<BottomNavigationView>(R.id.navigation)
        assertTrue(!navigation.menu.findItem(R.id.navMessages).isVisible)
        assertTrue(ViewModelProvider(activity)[DashboardViewModel::class.java].upcomingSelected)
        assertTrue(CampusSection.MESSAGES !in ViewModelProvider(activity)[DashboardViewModel::class.java].enabledSections)
        activity.setSectionEnabled("MESSAGES", true)
        activity.setSectionEnabled("NOTICES", false)
        assertTrue(navigation.menu.findItem(R.id.navMessages).isVisible)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get(); navigation = activity.findViewById(R.id.navigation)
        assertTrue(navigation.menu.findItem(R.id.navMessages).isVisible)
        assertTrue(!navigation.menu.findItem(R.id.navNotices).isVisible)
        controller.pause().stop().destroy()
    }
    @Test fun allTabsCanBeSelectedRepeatedly() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val navigation = activity.findViewById<BottomNavigationView>(R.id.navigation)
        repeat(3) {
            listOf(R.id.navCourses to "日程", R.id.navExams to "考试",
                R.id.navMessages to "作业", R.id.navNotices to "通知", R.id.navSettings to "设置").forEach { (id, title) ->
                navigation.selectedItemId = id
                assertEquals(title, activity.findViewById<TextView>(R.id.pageTitle).text.toString())
            }
        }
        controller.pause().stop().destroy()
    }

    @Test fun validLoginReturnsAndRefreshesButCasDoesNot() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.loginVerifier = { _, _ -> true }
        activity.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = R.id.navSettings
        activity.findViewById<View>(R.id.accounts).performClick()
        val web = activity.findViewById<WebView>(R.id.webView)
        web.webViewClient.onPageFinished(web, "https://cas.whu.edu.cn/authserver/login")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.webContainer).visibility)
        web.webViewClient.onPageFinished(web, "https://zhlj.whu.edu.cn/pc/toIndex")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.webContainer).visibility)
        assertTrue(ViewModelProvider(activity)[DashboardViewModel::class.java]
            .states.value.filterKeys { it != CampusSection.MESSAGES }.values.all { it.needsLogin })
        controller.pause().stop().destroy()
    }

    @Test fun failedLoginCheckKeepsLoginOpenAndManualDoneReturns() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.loginVerifier = { _, _ -> false }
        activity.findViewById<BottomNavigationView>(R.id.navigation).selectedItemId = R.id.navSettings
        activity.findViewById<View>(R.id.accounts).performClick()
        val web = activity.findViewById<WebView>(R.id.webView)
        web.webViewClient.onPageFinished(web, "https://zhlj.whu.edu.cn/pc/toIndex")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.webContainer).visibility)
        activity.findViewById<MaterialButton>(R.id.closeWeb).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.webContainer).visibility)
        controller.pause().stop().destroy()
    }

    @Test fun savedDayAndNightModesAreAppliedOnLaunch() {
        listOf(AppCompatDelegate.MODE_NIGHT_YES to Configuration.UI_MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_NO to Configuration.UI_MODE_NIGHT_NO).forEach { (mode, expected) ->
            RuntimeEnvironment.getApplication().getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .edit().putInt("mode", mode).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            assertEquals(expected, controller.get().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            controller.pause().stop().destroy()
        }
    }
}
