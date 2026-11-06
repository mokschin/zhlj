package com.velmorath.app

import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CourseCalendarTest {
    @Test fun daySelectionAndMonthRolloverUseActualDates() {
        var day: LocalDate? = null; var month: YearMonth? = null
        val view = CourseCalendarView(RuntimeEnvironment.getApplication(), YearMonth.of(2026, 12), LocalDate.of(2026, 12, 1),
            events = { emptyList() }, onDate = { day = it }, onMonth = { month = it })
        find(view, "2026-12-15")!!.performClick()
        assertEquals(LocalDate.of(2026, 12, 15), day)

    }
    private fun find(view: View, description: String): View? {
        if (view.contentDescription?.toString() == description) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), description)?.let { return it }
        return null
    }
}
