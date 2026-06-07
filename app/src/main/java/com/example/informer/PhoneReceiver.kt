package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log

class PhoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("PHONE_RECEIVER", "onReceive action=${intent.action}")
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        Log.d("PHONE_RECEIVER", "state=$state")
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val numberFromIntent = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val formattedNumber = if (numberFromIntent.isNullOrBlank()) null
        else PhoneUtils.formatVietnamesePhoneNumber(numberFromIntent)

        // Hỗ trợ Direct Boot cho SharedPreferences
        val safeContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && !context.isDeviceProtectedStorage) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

        // Bỏ qua nếu là số chính chủ của thiết bị
        val sharedPref = safeContext.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val myPhone = sharedPref.getString("my_phone", "") ?: ""
        if (formattedNumber != null && formattedNumber == myPhone) {
            Log.d("PHONE_RECEIVER", "Bo qua so chinh chu.")
            return
        }

        if (formattedNumber == null) {
            Log.d("PHONE_RECEIVER", "Chua co so, bo qua.")
            return
        }
        val displayName = PhoneUtils.getDisplayName(context, formattedNumber)

        // Dedup window 60 giây để tránh trigger nhiều lần
        val dedupeKey = formattedNumber ?: "hidden"
        val dedupePref = safeContext.getSharedPreferences("CallDedupe", Context.MODE_PRIVATE)
        val currentTime = System.currentTimeMillis()
        if (currentTime - dedupePref.getLong(dedupeKey, 0) < 60_000) {
            Log.d("PHONE_RECEIVER", "Bo qua trung lap: $dedupeKey")
            return
        }
        dedupePref.edit().putLong(dedupeKey, currentTime).apply()

        Log.d("PHONE_RECEIVER", "Cuoc goi den: $displayName")

        val pendingResult = goAsync()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Informer:CallWakeLock")
        Log.d("PHONE_RECEIVER", "Acquiring wakeLock và chuẩn bị gửi call event")
        wakeLock.acquire(30000)

        kotlin.concurrent.thread {
            try {
                // Chờ 3 giây trước gửi để batch các cuộc gọi khác nếu có
                Thread.sleep(3_000)

                Log.d("PHONE_RECEIVER", "Gửi call event lên server number=$displayName")
                ServerReporter.sendEventSync(
                    context = context.applicationContext,
                    type = "CALL",
                    incomingNumber = displayName,
                    content = "Cuộc gọi đến đang đổ chuông..."
                )
            } catch (e: Exception) {
                Log.e("PHONE_RECEIVER", "Loi: ${e.message}")
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                Log.d("PHONE_RECEIVER", "Đã release wakeLock và finish pendingResult")
                pendingResult.finish()
            }
        }
    }
}
