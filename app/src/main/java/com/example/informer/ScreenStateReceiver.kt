package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ScreenStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("ServiceState", Context.MODE_PRIVATE)
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.d("SCREEN_STATE", "📱 Màn hình BẬT -> Thiết lập trạng thái sử dụng tích cực (Ping 1 phút)")
                prefs.edit().putBoolean("is_screen_on", true).putInt("backoff_level", 0).apply()
                // Gọi đánh thức lập tức để bắt đầu chu kỳ nhanh
                AppLifecycleManager.ensureBackgroundRunning(context, "SCREEN_ON_WAKE")
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d("SCREEN_STATE", "💤 Màn hình TẮT -> Chuẩn bị leo thang chu kỳ ping")
                prefs.edit().putBoolean("is_screen_on", false).putInt("backoff_level", 0).apply()
            }
        }
    }
}
