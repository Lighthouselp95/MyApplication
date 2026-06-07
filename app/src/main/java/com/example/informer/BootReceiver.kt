package com.example.informer

import android.content.BroadcastReceiver
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

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BOOT_RECEIVER", "onReceive action=$action")
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d("BOOT_RECEIVER", "Bỏ qua action không hỗ trợ: $action")
            return
        }

        // Sử dụng context an toàn cho Direct Boot nếu cần
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !context.isDeviceProtectedStorage) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

        // Di chuyển SharedPreferences sang Device Protected Storage nếu chưa có (chỉ chạy 1 lần)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            safeContext.moveSharedPreferencesFrom(context, "AppConfig")
        }

        val sharedPref = safeContext.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val phone = sharedPref.getString("my_phone", "") ?: ""
        val token = sharedPref.getString("token", "") ?: ""
        Log.d("BOOT_RECEIVER", "Đọc prefs sau boot: phone=${phone.isNotEmpty()} token=${token.isNotEmpty()}")

        // Chỉ restart nếu đã kích hoạt trước đó
        if (phone.isEmpty() || token.isEmpty()) {
            Log.d("BOOT_RECEIVER", "⏭️ Chưa kích hoạt, không restart.")
            return
        }

        Log.d("BOOT_RECEIVER", "🚀 Khởi động lại service và WorkManager.")

        // 1. Restart Foreground Service
        val serviceIntent = Intent(context, BackgroundMonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d("BOOT_RECEIVER", "Gọi startForegroundService cho BackgroundMonitoringService")
            context.startForegroundService(serviceIntent)
        } else {
            Log.d("BOOT_RECEIVER", "Gọi startService cho BackgroundMonitoringService")
            context.startService(serviceIntent)
        }

        // 2. Re-register WorkManager (phòng trường hợp bị xóa sau reboot)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag("INFORMER_SYNC_WORK")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "InformerHardwareSyncTask",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Log.d("BOOT_RECEIVER", "Đã enqueue UniquePeriodicWork InformerHardwareSyncTask")

        // 3. Gửi event báo thiết bị vừa boot
        kotlin.concurrent.thread {
            Log.d("BOOT_RECEIVER", "Đang gửi BOOT event lên server")
            ServerReporter.sendEventSync(
                context = context,
                type = "BOOT",
                incomingNumber = "HE_THONG",
                content = "Thiet bi vua khoi dong lai."
            )
        }
    }
}
