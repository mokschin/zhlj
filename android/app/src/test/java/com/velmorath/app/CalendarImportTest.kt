package com.velmorath.app

import android.content.*
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CalendarImportTest {
    class Provider : ContentProvider() {
        val rows = linkedMapOf<Long, ContentValues>()
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
            val cursor = MatrixCursor(projection ?: arrayOf("_id"))
            rows.forEach { (id, value) ->
                if (value.getAsLong(CalendarContract.Events.CALENDAR_ID).toString() == selectionArgs!![0] && value.getAsString(CalendarContract.Events.DESCRIPTION) == selectionArgs[1]) cursor.addRow(arrayOf(id))
            }
            return cursor
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = (rows.keys.maxOrNull() ?: 0) + 1; rows[id] = ContentValues(values)
            return ContentUris.withAppendedId(uri, id)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            rows[ContentUris.parseId(uri)] = ContentValues(values); return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
    @Test fun reimportUpdatesInsteadOfDuplicatingAndKeepsCalendarBoundary() {
        val context = RuntimeEnvironment.getApplication()
        val provider = Provider()
        provider.attachInfo(context, ProviderInfo().apply { authority = CalendarContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
        val course = CampusItem("课程", course = CourseSlot(1, 1, 2, "101", "老师"))
        val events = UpcomingEvent.all(listOf(CampusFeed(listOf(course), weekStart = "2026-11-02")), null, emptyList())
        CalendarWriter.upsert(context.contentResolver, 3, events)
        CalendarWriter.upsert(context.contentResolver, 3, events.map { it.copy(room = "202") })
        assertEquals(1, provider.rows.size)
        val row = provider.rows.values.single()
        assertEquals("202", row.getAsString(CalendarContract.Events.EVENT_LOCATION))
        assertEquals("Asia/Shanghai", row.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertEquals(events.single().start.atZone(CourseTimes.zone).toInstant().toEpochMilli(), row.getAsLong(CalendarContract.Events.DTSTART).toLong())
        CalendarWriter.upsert(context.contentResolver, 4, events)
        assertEquals(2, provider.rows.size)
    }
}
