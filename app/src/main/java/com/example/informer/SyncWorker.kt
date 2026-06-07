package com.example.informer

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Hỗ trợ Direct Boot bằng cách sử dụng Device Protected Storage
        val safeContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            applicationContext.createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }

        val sharedPref = safeContext.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val phone = sharedPref.getString("my_phone", "") ?: ""
        val token = sharedPref.getString("token", "") ?: ""
        Log.d("SYNC_WORKER", "doWork() phoneSet=${phone.isNotEmpty()} tokenSet=${token.isNotEmpty()}")

        if (phone.isEmpty() || token.isEmpty()) {
            Log.d("SYNC_WORKER", "⏭️ Chưa kích hoạt, bỏ qua.")
            MainActivity.addLog("⏭️ [Heartbeat] Bỏ qua vì chưa kích hoạt")
            return@withContext Result.success()
        }

        return@withContext try {
            MainActivity.addLog("🔄 [Heartbeat] Đang gửi...")
            val ok = ServerReporter.sendEventSync(
                context = applicationContext,
                type = "HEARTBEAT",
                incomingNumber = "HE_THONG",
                content = "Thiet bi dang online - kiem tra dinh ky tu dong."
            )
            if (ok) {
                Log.d("SYNC_WORKER", "✅ Heartbeat OK")
                Result.success()
            } else {
                Log.w("SYNC_WORKER", "⚠️ Server không phản hồi, retry.")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("SYNC_WORKER", "❌ ${e.message}")
            Result.retry()
        }
    }
}
