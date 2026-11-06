package com.velmorath.app

import android.Manifest
import android.app.DatePickerDialog
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

internal class CalendarImport(private val activity: MainActivity, private val model: DashboardViewModel) {
    fun show() {
        if (listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).any { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }) {
            activity.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 602)
            return
        }
        val month = model.courseMonth
        DatePickerDialog(activity, { _, year, m, _ -> load(YearMonth.of(year, m + 1)) }, month.year, month.monthValue - 1, 1).apply { setTitle("选择要导入的月份") }.show()
    }
    private fun load(month: YearMonth) {
        val progress = MaterialAlertDialogBuilder(activity).setMessage("正在读取${month.monthValue}月课程…").setCancelable(false).create()
        val job = activity.lifecycleScope.launch {
            try {
                val cookie = android.webkit.CookieManager.getInstance().getCookie("https://zhlj.whu.edu.cn/pc/index").orEmpty()
                val feeds = model.monthFeeds(cookie, month)
                val events = UpcomingEvent.all(feeds, null, emptyList()).filter { YearMonth.from(it.date) == month }
                val calendars = withContext(Dispatchers.IO) {
                    val result = mutableListOf<Pair<Long, String>>()
                    activity.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                        arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { cursor ->
                        while (cursor.moveToNext()) result += cursor.getLong(0) to cursor.getString(1)
                    }; result
                }
                progress.dismiss()
                when {
                    events.isEmpty() -> toast("该月没有可导入的课程")
                    calendars.isEmpty() -> toast("未找到可写日历，请先在系统日历中添加账户")
                    else -> MaterialAlertDialogBuilder(activity).setTitle("导入 $month 的 ${events.size} 次课程")
                        .setItems(calendars.map { it.second }.toTypedArray()) { _, index ->
                            MaterialAlertDialogBuilder(activity).setTitle("导入到 ${calendars[index].second}？")
                                .setMessage("$month，共 ${events.size} 次课程。已导入课程将更新，不会重复添加。")
                                .setPositiveButton("导入") { _, _ -> write(calendars[index].first, events) }.setNegativeButton("取消", null).show()
                        }.setNegativeButton("取消", null).show()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { progress.dismiss(); toast("无法读取完整课程或日历，请检查登录和权限后重试") }
            finally { progress.dismiss() }
        }
        progress.setButton(-2, "取消") { _, _ -> job.cancel() }
        progress.show()
    }
    private fun write(calendarId: Long, events: List<UpcomingEvent>) {
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CalendarWriter.upsert(activity.contentResolver, calendarId, events)
                }
                toast("已导入 ${events.size} 次课程")
            } catch (_: Exception) { toast("导入失败，请检查日历权限后重试") }
        }
    }
    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}

internal object CalendarWriter {
    fun upsert(resolver: android.content.ContentResolver, calendarId: Long, events: List<UpcomingEvent>) {
                    val operations = arrayListOf<ContentProviderOperation>()
                    events.forEach { event ->
                        val description = "Velmorath:${event.key}"
                        var id: Long? = null
                        resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
                            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DESCRIPTION} = ? AND ${CalendarContract.Events.DELETED} = 0", arrayOf(calendarId.toString(), description), null)?.use { if (it.moveToFirst()) id = it.getLong(0) }
                        val operation = if (id == null) ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                            else ContentProviderOperation.newUpdate(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id!!))
                        operations += operation.withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                            .withValue(CalendarContract.Events.TITLE, event.item.title)
                            .withValue(CalendarContract.Events.EVENT_LOCATION, event.room)
                            .withValue(CalendarContract.Events.DESCRIPTION, description)
                            .withValue(CalendarContract.Events.DTSTART, event.start.atZone(CourseTimes.zone).toInstant().toEpochMilli())
                            .withValue(CalendarContract.Events.DTEND, event.end.atZone(CourseTimes.zone).toInstant().toEpochMilli())
                            .withValue(CalendarContract.Events.EVENT_TIMEZONE, CourseTimes.zone.id).build()
                    }
                    resolver.applyBatch(CalendarContract.AUTHORITY, operations)
    }
}
