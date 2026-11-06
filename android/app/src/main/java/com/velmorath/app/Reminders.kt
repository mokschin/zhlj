package com.velmorath.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

internal object Reminders {
    fun prefs(context: Context) = context.getSharedPreferences("messages", Context.MODE_PRIVATE)
    fun events(context: Context): List<UpcomingEvent> {
        val store = ScheduleStore(context)
        return UpcomingEvent.all(store.feeds(), store.exams(), store.personal())
    }
    fun type(event: UpcomingEvent) = if (event.exam) "exam" else if (event.custom) "personal" else "course"
    fun lead(context: Context, type: String) = prefs(context).getInt("${type}_minutes", if (type == "exam") 60 else 15)
    fun permitted(context: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun channel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("schedule", "日程与考试提醒", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("notices", "订阅的校园通知", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun show(context: Context, key: String, title: String, text: String, channel: String = "schedule") {
        if (!permitted(context)) return
        channel(context)
        val intent = Intent(context, MainActivity::class.java).putExtra("open_notices", channel == "notices")
        val open = PendingIntent.getActivity(context, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notice).setContentTitle(title)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(context).notify(key, 0, notification) } catch (_: SecurityException) { }
    }
    private fun pending(context: Context, key: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction("com.velmorath.REMIND").setData(Uri.parse("velmorath://reminder/${Uri.encode(key)}")).putExtra("key", key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @Synchronized fun schedule(context: Context) {
        val prefs = prefs(context)
        val manager = context.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now(CourseTimes.zone)
        val selected = events(context).filter { prefs.getBoolean(type(it), false) && it.end > now && it.start <= now.plusDays(35) }
        val old = runCatching { JSONObject(prefs.getString("alarms", "{}")!!) }.getOrDefault(JSONObject())
        val current = JSONObject()
        selected.forEach { event ->
            val at = event.start.minusMinutes(lead(context, type(event)).toLong())
            if (event.start <= now) return@forEach
            val requested = at.atZone(CourseTimes.zone).toInstant().toEpochMilli()
            val timestamp = if (at <= now) old.optLong(event.key).takeIf { it > 0 } ?: System.currentTimeMillis() + 1000 else requested
            current.put(event.key, timestamp)
            if (old.optLong(event.key) == timestamp) return@forEach
            val pending = pending(context, event.key)
            try {
                if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pending)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pending)
            } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pending) }
        }
        old.keys().asSequence().filter { !current.has(it) }.toList().forEach { manager.cancel(pending(context, it)) }
        prefs.edit().putString("alarms", current.toString()).apply()
    }
    fun configureWorker(context: Context) {
        val p = prefs(context)
        val manager = WorkManager.getInstance(context)
        if (listOf("notice", "course", "exam").any { p.getBoolean(it, false) }) {
            val work = PeriodicWorkRequestBuilder<CampusSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            manager.enqueueUniquePeriodicWork("campus-subscriptions", ExistingPeriodicWorkPolicy.KEEP, work)
        } else manager.cancelUniqueWork("campus-subscriptions")
        schedule(context)
    }
    @Synchronized fun processNotices(context: Context, items: List<CampusItem>, complete: Boolean = true) {
        val p = prefs(context)
        val chosen = p.getStringSet("notice_sources", emptySet()).orEmpty()
        val seen = p.getStringSet("notice_seen", emptySet()).orEmpty().toMutableSet()
        val baseline = p.getBoolean("notice_baseline", false)
        items.forEach { item ->
            val key = item.id.ifBlank { "${item.sourceId}:${item.title}:${item.publishedAt}" }
            if (baseline && key !in seen && item.sourceId in chosen && p.getBoolean("notice", false)) show(context, key, item.title, "${item.source} · ${item.publishedAt}", "notices")
            seen += key
        }
        p.edit().putStringSet("notice_seen", seen.toList().takeLast(5000).toSet()).putBoolean("notice_baseline", baseline || complete).apply()
        ScheduleStore(context).rememberSources(items)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val event = Reminders.events(context).firstOrNull { it.key == key } ?: return
        if (!Reminders.prefs(context).getBoolean(Reminders.type(event), false) || event.end <= LocalDateTime.now(CourseTimes.zone)) return
        Reminders.show(context, key, event.item.title, "${event.start.monthValue}/${event.start.dayOfMonth} ${event.time}  ${event.room}")
    }
}
class ScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        Reminders.prefs(context).edit().remove("alarms").apply()
        Reminders.configureWorker(context)
    }
}
class CampusSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = Reminders.prefs(context)
        val cookies = withContext(Dispatchers.Main) { CookieManager.getInstance().let { it.getCookie("https://zhlj.whu.edu.cn/pc/index").orEmpty() to it.getCookie("https://jwgl.whu.edu.cn/").orEmpty() } }
        val store = ScheduleStore(context)
        var failed = false
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: SessionExpiredException) { prefs.edit().putString("status", "登录已过期，请重新登录以同步提醒").apply() }
            catch (_: Exception) { failed = true }
            delay(3000)
        }
        if (prefs.getBoolean("notice", false) && cookies.first.isNotBlank()) attempt { ZhljApi.notices(cookies.first).let { Reminders.processNotices(context, it.items, !it.partial) } }
        if (prefs.getBoolean("course", false) && cookies.first.isNotBlank()) attempt {
            val today = FeedParser.today()
            val weeks = listOf(today, today.plusDays(7)).map { ZhljApi.courses(cookies.first, it).also { delay(3000) } }
            store.save(weeks, null)
        }
        if (prefs.getBoolean("exam", false) && cookies.second.isNotBlank()) attempt { store.save(emptyList(), ZhljApi.exams(cookies.second)) }
        Reminders.schedule(context)
        return if (failed) Result.retry() else Result.success()
    }
}
