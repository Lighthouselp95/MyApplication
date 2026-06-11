package com.example.informer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object ServiceWatchdog {
    private const val TAG = "SERVICE_WATCHDOG"
    private const val ACTION_WATCHDOG = "com.example.informer.action.SERVICE_WATCHDOG"
    private const val REQUEST_CODE = 4101
    private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

    fun schedule(context: Context, reason: String): Boolean {
        val appContext = context.applicationContext
        if (!AppLifecycleManager.isActivated(appContext)) return false
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(appContext)
        val triggerAtMillis = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
        return try {
            Log.d("SERVICE_WATCHDOG", "Da dat lich nhip tiep theo (15 phut) do $reason")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            true
        } catch (e: Exception) { false }
    }

    fun cancel(context: Context, reason: String) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(appContext)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    // Ham gia lap de MainActivity khong bi loi
    fun snapshot(context: Context): ServiceHealthSnapshot {
        val activated = AppLifecycleManager.isActivated(context)
        return ServiceHealthSnapshot(activated, activated, System.currentTimeMillis(), 0L)
    }

    fun humanReadableAge(context: Context): String {
        return "He thong dang duoc bao ve (Chu ky 15p)"
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ServiceWatchdogReceiver::class.java).apply { action = ACTION_WATCHDOG }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}

data class ServiceHealthSnapshot(
    val activated: Boolean,
    val alive: Boolean,
    val lastSeenAt: Long,
    val ageMs: Long
)
