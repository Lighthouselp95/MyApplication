package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private val handler = Handler(Looper.getMainLooper())
    }

    data class SmsData(val sender: String, val body: String, val timestamp: Long)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val smsTimestamp = messages[0].timestampMillis
        val rawNumber = messages[0].originatingAddress ?: "Unknown"
        val formattedNumber = DeviceUtils.formatVietnamesePhoneNumber(rawNumber)
        val fullContent = messages.joinToString("") { it.messageBody ?: "" }

        if (!AppLifecycleManager.isActivated(context)) {
            Log.d("SMS_RECEIVER", "App chưa kích hoạt, bỏ qua SMS.")
            return
        }

        Log.d("SMS_RECEIVER", "📩 SMS_RECEIVED from=$formattedNumber")

        val safeContext = context.deviceProtectedContext()
        val pendingResult = goAsync()
        
        // GIẢM DELAY: Chỉ đợi 300ms thay vì 400ms để bắt ID DB
        handler.postDelayed({
            try {
                val smsIdInDb = getSmsIdFromDb(safeContext, rawNumber, fullContent, smsTimestamp)
                val dedupePref = safeContext.getSharedPreferences("AppInternalStateV4", Context.MODE_PRIVATE)
                
                val cleanBody = fullContent.replace("\\s".toRegex(), "")
                val tsSec = smsTimestamp / 1000
                val msgKey = if (smsIdInDb != null) "ID_$smsIdInDb" else "KEY_${formattedNumber}_${cleanBody}_$tsSec"
                
                if (dedupePref.contains(msgKey)) {
                    Log.d("SMS_RECEIVER", "⏭️ SMS trùng lặp, bỏ qua (key=$msgKey)")
                    pendingResult.finish()
                    return@postDelayed
                }

                // Ghi nhật ký ngay để tránh trùng lặp nếu crash
                dedupePref.edit().putBoolean(msgKey, true).commit()

                val displayName = DeviceUtils.getDisplayName(context, formattedNumber)
                SmsBatchManager.enqueue(context, displayName, fullContent, smsTimestamp)
                pendingResult.finish()
            } catch (e: Exception) {
                Log.e("SMS_RECEIVER", "❌ Lỗi xử lý: ${e.message}")
                pendingResult.finish()
            }
        }, 300)
    }

    private fun getSmsIdFromDb(context: Context, address: String?, body: String, timestamp: Long): Long? {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                "address = ? AND body = ? AND date >= ? AND date <= ?",
                arrayOf(address, body, (timestamp - 5000L).toString(), (timestamp + 5000L).toString()),
                "date DESC LIMIT 1"
            )
            cursor?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (e: Exception) { null }
    }
}
