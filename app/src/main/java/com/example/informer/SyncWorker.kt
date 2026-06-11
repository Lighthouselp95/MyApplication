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
        val safeContext = applicationContext.deviceProtectedContext()

        if (!AppLifecycleManager.isActivated(safeContext)) {
            return@withContext Result.success()
        }

        return@withContext try {
            Log.d("SYNC_WORKER", "=== LUONG MANH NHAT DANG CHAY (20 PHUT) ===")
            MainActivity.addLog("🫀 [Ve si] Luong manh nhat (20 phut) dang kiem tra tong the...")
            
            val serviceIntent = Intent(applicationContext, BackgroundMonitoringService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(applicationContext, serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }

            val smsCount = SmsInboxSync.pollMissingSms(applicationContext, "WM_STRONG")
            val callCount = CallLogSync.pollMissingCalls(applicationContext, "WM_STRONG")
            
            if (smsCount > 0 || callCount > 0) {
                MainActivity.addLog("🛰️ [WM_STRONG] Da quet bu: SMS=\$smsCount, CALL=\$callCount")
            }

            val ok = ServerReporter.sendEventSync(
                context = applicationContext,
                type = "HEARTBEAT_STRONG",
                incomingNumber = "HE_THONG",
                content = "Ve si manh nhat da kiem tra va hoi sinh he thong."
            )

            if (ok) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e("SYNC_WORKER", "❌ Loi luong manh nhat: \${e.message}")
            Result.retry()
        }
    }
}
