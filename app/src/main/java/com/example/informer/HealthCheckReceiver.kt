package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class HealthCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("HEALTH_REPORT", "--- TRẠNG THÁI HỆ THỐNG ---")
        Log.d("HEALTH_REPORT", "Service running: ${isServiceRunning(context)}")
        Log.d("HEALTH_REPORT", "Activated: ${AppLifecycleManager.isActivated(context)}")
        val pendingSms = FailedSmsQueue.pendingCount(context)
        if (pendingSms > 0) {
            Log.d("HEALTH_REPORT", "Failed SMS queue: $pendingSms")
        }
    }

    private fun isServiceRunning(context: Context): Boolean {
        val prefs = context.getSharedPreferences("ServiceState", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_service_running", false)
    }
}