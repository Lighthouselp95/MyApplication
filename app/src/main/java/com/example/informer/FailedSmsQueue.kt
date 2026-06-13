package com.example.informer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Queue lưu SMS chưa gửi được khi mất mạng, tự động retry
 * Mỗi entry có tối đa 3 lần retry, sau 24h sẽ bị xóa
 */
object FailedSmsQueue {
    private const val PREFS_NAME = "FailedSmsQueue"
    private const val KEY_QUEUE = "queue"
    private const val MAX_RETRY = 3
    private const val MAX_ENTRIES = 200
    private const val TTL_MS = 24L * 60L * 60L * 1000L // 24 giờ

    data class QueuedSms(
        val sender: String,
        val body: String,
        val timestamp: Long,
        val retryCount: Int = 0,
        val firstFailedAt: Long = System.currentTimeMillis()
    )

    fun enqueue(context: Context, sender: String, body: String, timestamp: Long) {
        val safeContext = context.createDeviceProtectedStorageContext()
        val prefs = safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(this) {
            val entries = loadQueue(prefs)
            if (entries.size >= MAX_ENTRIES) return // quá tải, bỏ qua
            entries.add(QueuedSms(sender, body, timestamp))
            saveQueue(prefs, entries)
        }
    }

    fun dequeueAndRetry(context: Context): Int {
        val safeContext = context.createDeviceProtectedStorageContext()
        val prefs = safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        synchronized(this) {
            val entries = loadQueue(prefs)
            if (entries.isEmpty()) return 0

            val remaining = mutableListOf<QueuedSms>()
            var sentCount = 0

            for (sms in entries) {
                // Xóa nếu quá 24h
                if (now - sms.firstFailedAt > TTL_MS) continue

                val ok = ServerReporter.sendEventSync(
                    context = safeContext,
                    type = "SMS",
                    incomingNumber = sms.sender,
                    content = sms.body,
                    timestamp = sms.timestamp,
                    silent = false
                )

                if (ok) {
                    sentCount++
                } else if (sms.retryCount < MAX_RETRY) {
                    remaining.add(sms.copy(retryCount = sms.retryCount + 1))
                }
                // Nếu retryCount >= MAX_RETRY, bỏ qua (loại bỏ)
            }

            saveQueue(prefs, remaining)
            return sentCount
        }
    }

    fun pendingCount(context: Context): Int {
        val safeContext = context.createDeviceProtectedStorageContext()
        val prefs = safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(this) {
            return loadQueue(prefs).size
        }
    }

    private fun loadQueue(prefs: android.content.SharedPreferences): MutableList<QueuedSms> {
        val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<QueuedSms>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(QueuedSms(
                    sender = obj.getString("sender"),
                    body = obj.getString("body"),
                    timestamp = obj.getLong("timestamp"),
                    retryCount = obj.optInt("retry", 0),
                    firstFailedAt = obj.optLong("firstFailedAt", System.currentTimeMillis())
                ))
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveQueue(prefs: android.content.SharedPreferences, entries: List<QueuedSms>) {
        val now = System.currentTimeMillis()
        val array = JSONArray()
        entries
            .filter { now - it.firstFailedAt <= TTL_MS }
            .take(MAX_ENTRIES)
            .forEach { sms ->
                array.put(JSONObject().apply {
                    put("sender", sms.sender)
                    put("body", sms.body)
                    put("timestamp", sms.timestamp)
                    put("retry", sms.retryCount)
                    put("firstFailedAt", sms.firstFailedAt)
                })
            }
        prefs.edit().putString(KEY_QUEUE, array.toString()).commit()
    }
}