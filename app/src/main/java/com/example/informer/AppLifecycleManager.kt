package com.example.informer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AppLifecycleManager {
    private const val TAG = "APP_LIFECYCLE"
    private const val UNIQUE_SYNC_WORK = "InformerHardwareSyncTask"

    fun isActivated(context: Context): Boolean {
        val safeContext = context.deviceProtectedContext()
        val prefs = safeContext.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val phone = prefs.getString("my_phone", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        return phone.isNotBlank() && token.isNotBlank()
    }

    fun syncProtectedStorage(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dpContext = context.deviceProtectedContext()
            val prefsToMove = arrayOf("AppConfig", "SmsDedupe", "CallDedupe", "AppInternalStateV4", "AppLogHistory")
            for (pref in prefsToMove) {
                dpContext.moveSharedPreferencesFrom(context, pref)
            }
        }
    }

    fun restoreIfActivated(context: Context, source: String) {
        syncProtectedStorage(context)
        if (!isActivated(context)) {
            Log.d(TAG, "[$source] Skip restore: not activated")
            return
        }
        ensureBackgroundRunning(context, source)
    }

    fun ensureBackgroundRunning(context: Context, source: String) {
        val appContext = context.applicationContext
        syncProtectedStorage(appContext)
        
        Log.d(TAG, "[$source] Ensure background running")

        val cleanSource = source.substringAfterLast(".")
        val wakeMsg = when {
            source.contains("watchdog", ignoreCase = true) -> "👀 [Watchdog] Phát hiện service gián đoạn, đã hồi sinh hệ thống."
            source.contains("boot", ignoreCase = true) -> "🚀 [Hệ thống] Tự động khởi chạy sau khi khởi động máy."
            source.contains("worker", ignoreCase = true) -> "🫀 [Maintenance] Worker đã kiểm tra và đảm bảo nền hoạt động."
            source.contains("activate", ignoreCase = true) -> "✅ [Kích hoạt] Người dùng đã bật hệ thống."
            else -> "🧩 [$cleanSource] Khôi phục trạng thái nền."
        }
        MainActivity.addLog(wakeMsg)
        
        if (HistoryScanBaseline.ensureInitialized(appContext)) {
            MainActivity.addLog("📍 Đã thiết lập mốc quét từ lúc cài đặt.")
        }
        
        startMonitoringService(appContext, source)
        
        if (schedulePeriodicSync(appContext, source)) {
            MainActivity.addLog("🚀 Đã lập lịch đồng bộ ngầm chu kỳ 15 phút bằng WorkManager.")
        }
        
        ServiceWatchdog.schedule(appContext, source)
        
        MainActivity.addLog("🧩 [$cleanSource] Nền đã sẵn sàng")
    }

    private fun schedulePeriodicSync(context: Context, source: String): Boolean {
        if (isWorkActive(context, UNIQUE_SYNC_WORK)) {
            Log.d(TAG, "[$source] schedulePeriodicSync() skip active uniqueWork=$UNIQUE_SYNC_WORK")
            return false
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(20, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag("INFORMER_SYNC_WORK")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        return true
    }

    private fun startMonitoringService(context: Context, source: String) {
        val serviceIntent = Intent(context, BackgroundMonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun isWorkActive(context: Context, uniqueWorkName: String): Boolean {
        return runCatching {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(uniqueWorkName)
                .get()
            infos.any { it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.BLOCKED }
        }.getOrDefault(false)
    }
}
