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

                // Hủy alarm cũ và đặt lại alarm 1 phút (để AM chuyển sang chế độ screen on ngay)
                rescheduleAlarm(context)
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d("SCREEN_STATE", "💤 Màn hình TẮT -> Đặt alarm 5 phút để thức dậy")
                prefs.edit().putBoolean("is_screen_on", false).putInt("backoff_level", 0).apply()
                // Đặt alarm 5 phút sau khi tắt màn hình để đảm bảo app thức dậy
                rescheduleAlarmAfterScreenOff(context)
            }
        }
    }

    private fun rescheduleAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = android.content.Intent(context, AlarmReceiver::class.java)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 60_000L, // 1 phút
                pendingIntent
            )
            Log.d("SCREEN_STATE", "✅ Đã đặt lại AlarmReceiver 1 phút (khi màn hình bật)")
        } catch (e: Exception) {
            Log.e("SCREEN_STATE", "❌ Lỗi đặt lại Alarm: ${e.message}")
        }
    }

    private fun rescheduleAlarmAfterScreenOff(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = android.content.Intent(context, AlarmReceiver::class.java)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            // Đặt alarm 5 phút sau khi tắt màn hình
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 5 * 60_000L, // 5 phút
                pendingIntent
            )
            Log.d("SCREEN_STATE", "✅ Đã đặt AlarmReceiver 5 phút sau khi tắt màn hình")
        } catch (e: Exception) {
            Log.e("SCREEN_STATE", "❌ Lỗi đặt Alarm screen off: ${e.message}")
        }
    }
}
