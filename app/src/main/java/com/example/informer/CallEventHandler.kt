package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log

object CallEventHandler {
    private const val DEDUPE_WINDOW_MS = 60_000L
    private val dedupeLock = Any()

    fun handleIncomingCall(
        context: Context,
        intent: Intent,
        source: String,
        pendingResult: BroadcastReceiver.PendingResult? = null
    ) {
        try {
            Log.d("CALL_HANDLER", "[$source] onReceive action=${intent.action}")
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                Log.d("CALL_HANDLER", "[$source] Bo qua vi khong phai PHONE_STATE_CHANGED")
                finishPending(pendingResult)
                return
            }


            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state.isNullOrBlank()) {
                Log.d("CALL_HANDLER", "[$source] Bo qua vi state null/blank")
                finishPending(pendingResult)
                return
            }

            Log.d("CALL_HANDLER", "[$source] state=$state")
            if (state != TelephonyManager.EXTRA_STATE_RINGING) {
                Log.d("CALL_HANDLER", "[$source] Bo qua vi state khong phai RINGING")
                finishPending(pendingResult)
                return
            }

            val numberFromIntent = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            Log.d("CALL_HANDLER", "[$source] rawIncomingNumber=${numberFromIntent ?: "<null>"}")
            val formattedNumber = if (numberFromIntent.isNullOrBlank()) null
            else DeviceUtils.formatVietnamesePhoneNumber(numberFromIntent)

            val safeContext = context.deviceProtectedContext()

            val sharedPref = safeContext.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            val myPhone = sharedPref.getString("my_phone", "") ?: ""
            if (formattedNumber != null && formattedNumber == myPhone) {
                Log.d("CALL_HANDLER", "[$source] Bo qua so chinh chu: $formattedNumber")
                finishPending(pendingResult)
                return
            }

            if (formattedNumber == null) {
                Log.d("CALL_HANDLER", "[$source] Khong lay duoc so goi den, bo qua call event.")
                finishPending(pendingResult)
                return
            }

            val displayName = DeviceUtils.getDisplayName(context, formattedNumber)
            Log.d("CALL_HANDLER", "[$source] displayName=$displayName formattedNumber=$formattedNumber")

            val dedupePref = safeContext.getSharedPreferences("CallDedupe", Context.MODE_PRIVATE)
            val currentTime = System.currentTimeMillis()
            synchronized(dedupeLock) {
                val lastSeenTime = dedupePref.getLong(formattedNumber, 0)
                if (currentTime - lastSeenTime < DEDUPE_WINDOW_MS) {
                    Log.d("CALL_HANDLER", "[$source] Bo qua trung lap: $formattedNumber lastSeen=$lastSeenTime now=$currentTime")
                    finishPending(pendingResult)
                    return
                }
                dedupePref.edit().putLong(formattedNumber, currentTime).commit()
                Log.d("CALL_HANDLER", "[$source] Da ghi dedupe: $formattedNumber at=$currentTime")
            }

            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Informer:CallWakeLock")
            Log.d("CALL_HANDLER", "[$source] Acquiring wakeLock và chuẩn bị gửi call event")
            wakeLock.acquire(30_000)

            kotlin.concurrent.thread {
                try {
                    Log.d("CALL_HANDLER", "[$source] Dang gui call event len server number=$displayName raw=$numberFromIntent")
                    ServerReporter.sendEventSync(
                        context = context.applicationContext,
                        type = "CALL",
                        incomingNumber = displayName,
                        content = "Cuộc gọi đến đang đổ chuông..."
                    )
                    Log.d("CALL_HANDLER", "[$source] Da goi xong ServerReporter cho CALL")
                } catch (e: Exception) {
                    Log.e("CALL_HANDLER", "[$source] Loi: ${e.message}")
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                    Log.d("CALL_HANDLER", "[$source] Da release wakeLock va finish pendingResult")
                    finishPending(pendingResult)
                }
            }
        } catch (e: Exception) {
            Log.e("CALL_HANDLER", "[$source] Loi: ${e.message}")
            finishPending(pendingResult)
        }
    }

    private fun finishPending(pendingResult: BroadcastReceiver.PendingResult?) {
        try {
            pendingResult?.finish()
        } catch (_: Exception) {
        }
    }
}
