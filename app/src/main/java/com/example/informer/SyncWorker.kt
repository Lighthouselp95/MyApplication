package com.example.informer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val appContext = applicationContext
        
        if (!AppLifecycleManager.isActivated(appContext)) {
            return@withContext Result.success()
        }

        // Kiểm tra AM còn sống không (heartbeat trong 10 phút gần đây)
        val prefs = appContext.getSharedPreferences("ServiceState", Context.MODE_PRIVATE)
        val lastHeartbeat = prefs.getLong("last_alarm_heartbeat", 0L)
        val amAlive = (System.currentTimeMillis() - lastHeartbeat) < 10 * 60 * 1000L
        
        if (!amAlive && !isServiceRunning(appContext)) {
            Log.d("SYNC_WORKER", "🫀 [SyncWorker] AM chết & Service đã chết, gọi đánh thức!")
            MainActivity.addLog("🫀 [SyncWorker] Service đã chết, đang tự hồi sinh...")
            AppLifecycleManager.ensureBackgroundRunning(appContext, "WM_STRONG_WAKEUP")
        } else if (!amAlive) {
            Log.d("SYNC_WORKER", "✅ AM chết nhưng Service vẫn sống.")
        } else {
            Log.d("SYNC_WORKER", "⏺ AM còn sống, WorkManager bỏ qua.")
        }

        return@withContext try {
            val ok = ServerReporter.sendEventSync(
                context = appContext,
                type = "HEARTBEAT_STRONG",
                incomingNumber = "HE_THONG",
                content = "Ve si manh nhat da kiem tra, service ok.",
                silent = true
            )

            if (ok) {
                MainActivity.addLog("🫀 [SYS] Kiểm tra Service hoàn tất (20p)")
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("SYNC_WORKER", "❌ Loi luong manh nhat: ${e.message}")
            Result.retry()
        }
    }

    private fun isServiceRunning(context: Context): Boolean {
        val prefs = context.getSharedPreferences("ServiceState", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_service_running", false)
    }

    private fun isWatchdogScheduled(context: Context): Boolean {
        // Watchdog đã bị vô hiệu hóa, trả về true để SyncWorker không cố đánh thức nó
        return true
    }
}
