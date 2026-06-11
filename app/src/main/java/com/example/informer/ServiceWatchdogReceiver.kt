package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class ServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("SERVICE_WATCHDOG", "SW 10 phut dang thuc day he thong...")
        MainActivity.addLog("🚀 [Hoi sinh] Watchdog dang dung Service day...")

        // Duy tri vong lap
        ServiceWatchdog.schedule(context, "receiver_tick")

        if (!AppLifecycleManager.isActivated(context)) return

        // Goi service chay (neu dang chay thi khong sao, neu chet thi se song lai)
        val serviceIntent = Intent(context, BackgroundMonitoringService::class.java)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("SERVICE_WATCHDOG", "Loi SW khoi dong lai service: " + e.message)
        }
    }
}
