package com.example.informer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {

    /**
     * Lịch leo thang delay khi màn hình tắt/thiết bị idle.
     * Màn hình sáng: 1 phút (giữ app sống).
     * Màn hình tắt: leo thang đến 15 phút rồi ổn định.
     */
    private val delaySchedule = longArrayOf(
        3 * 60_000L,       // 3 phút
        5 * 60_000L,       // 5 phút
        10 * 60_000L,      // 10 phút
        15 * 60_000L,      // 15 phút (mốc tối đa — lặp lại)
    )

    /** Delay khi màn hình sáng (người dùng đang dùng máy) — 1 phút để app sống */
    private val screenOnDelay = 60_000L  // 1 phút

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ALARM_RECEIVER", "⏰ [Nhịp Tim] Thức dậy kiểm tra hệ thống...")
        
        // 1. Ghi heartbeat để WM biết AM còn sống
        val prefs = context.getSharedPreferences("ServiceState", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_alarm_heartbeat", System.currentTimeMillis()).apply()
        
        // 2. Thực hiện hồi sinh / kiểm tra Service
        AppLifecycleManager.ensureBackgroundRunning(context, "ALARM_TICK")

        // 3. Gửi ping silent lên server (thay vì đợi WorkManager)
        sendSilentPing(context)
        
        // 4. Lên lịch báo thức kế tiếp
        scheduleNext(context)
    }

    /**
     * Gửi heartbeat silent (im lặng) lên server qua AM,
     * không cần phụ thuộc WorkManager.
     */
    private fun sendSilentPing(context: Context) {
        kotlin.concurrent.thread {
            try {
                val ok = ServerReporter.sendEventSync(
                    context = context.applicationContext,
                    type = "HEARTBEAT",
                    incomingNumber = "HE_THONG",
                    content = "AM heartbeat — he thong on dinh.",
                    silent = true
                )
                Log.d("ALARM_RECEIVER", "📡 Ping server: ${if (ok) "OK" else "FAIL"}")
            } catch (e: Exception) {
                Log.e("ALARM_RECEIVER", "❌ Lỗi ping server: ${e.message}")
            }
        }
    }

    private fun scheduleNext(context: Context) {
        val prefs = context.getSharedPreferences("ServiceState", Context.MODE_PRIVATE)
        // Mặc định false (cẩn thận) - nếu không chắc thì coi như màn hình tắt
        val isScreenOn = prefs.getBoolean("is_screen_on", false)
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Kiểm tra POWER + IDLE state để quyết định leo thang
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isDeviceIdle = powerManager.isDeviceIdleMode()  // true khi màn hình tắt lâu
        
        val nextDelay: Long
        if (isScreenOn && !isDeviceIdle) {
            // Màn hình sáng + thiết bị không idle → người dùng đang dùng thật
            nextDelay = screenOnDelay
            prefs.edit().putInt("backoff_level", 0).apply()
            Log.d("ALARM_RECEIVER", "📱 Người dùng đang dùng máy. Ping ${screenOnDelay / 60_000} phút tiếp theo.")
        } else {
            // Màn hình tắt hoặc thiết bị idle → leo thang
            var level = prefs.getInt("backoff_level", 0)
            nextDelay = delaySchedule[level.coerceIn(0, delaySchedule.size - 1)]
            
            // Leo lên nấc tiếp theo cho lần sau
            if (level < delaySchedule.size - 1) {
                level++
                prefs.edit().putInt("backoff_level", level).apply()
            }
            Log.d("ALARM_RECEIVER", "💤 Màn hình tắt/idle. Leo thang lên nấc $level, delay tiếp theo: ${nextDelay / 60000} phút.")
        }

        // Kiểm tra quyền Exact Alarm trước khi gọi setExact (chỉ có từ API 31+)
        val hasExactAlarmPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
        if (hasExactAlarmPermission) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + nextDelay,
                    pendingIntent
                )
            } catch (e: SecurityException) {
                Log.w("ALARM_RECEIVER", "⚠️ Không có quyền Exact Alarm (SecurityException): ${e.message}")
                // Fallback sang alarm bình thường nếu thiếu quyền
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + nextDelay,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e("ALARM_RECEIVER", "❌ Lỗi không xác định khi đặt báo thức: ${e.message}")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + nextDelay,
                    pendingIntent
                )
            }
        } else {
            // Không có quyền Exact Alarm -> dùng alarm bình thường ngay từ đầu
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + nextDelay,
                pendingIntent
            )
            Log.d("ALARM_RECEIVER", "ℹ️ Thiết bị không hỗ trợ Exact Alarm. Dùng alarm thường.")
        }
    }
}
