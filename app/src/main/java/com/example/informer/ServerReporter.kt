package com.example.informer

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ServerReporter {
    private const val SERVER_API_URL = "https://portal-mirroring.onrender.com/api/push"
    private const val MAX_RETRY = 2

    fun sendEventSync(
        context: Context,
        type: String,
        incomingNumber: String,
        content: String,
        timestamp: Long? = null,
        silent: Boolean = false
    ): Boolean {
        val safeContext = context.createDeviceProtectedStorageContext()
        val sharedPref = safeContext.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val myPhone = sharedPref.getString("my_phone", "") ?: ""
        val token = sharedPref.getString("token", "") ?: ""

        if (myPhone.isEmpty()) {
            Log.w("SERVER", "Bỏ qua gửi vì myPhone rỗng")
            return false
        }

        repeat(MAX_RETRY) { attempt ->
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(SERVER_API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

                val eventDate = if (timestamp != null) Date(timestamp) else Date()
                val timeStr = SimpleDateFormat("HH:mm:ss d/M/yyyy", Locale.getDefault()).format(eventDate)

                val json = JSONObject().apply {
                    put("myPhoneNumber", myPhone)
                    put("token", token)
                    put("type", type.uppercase())
                    put("incomingNumber", incomingNumber)
                    put("content", content)
                    put("time", timeStr)
                }

                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code in 200..299) {
                    if (!silent) MainActivity.addLog("✅ [$type] Gửi thành công")
                    return true
                }
                Log.w("SERVER", "Server trả về $code (Lần ${attempt + 1})")
                if (!silent) MainActivity.addLog("⚠️ [$type] Lỗi HTTP $code (Lần ${attempt + 1})")

            } catch (e: Exception) {
                Log.e("SERVER", "Attempt ${attempt + 1} lỗi: ${e.message}")
            } finally {
                conn?.disconnect()
            }

            if (attempt < MAX_RETRY - 1) {
                try { Thread.sleep(2_000) } catch (_: InterruptedException) {}
            }
        }

        MainActivity.addLog("❌ [$type] Gửi thất bại sau $MAX_RETRY lần thử.")
        return false
    }

    fun sendEvent(
        context: Context,
        type: String,
        incomingNumber: String,
        content: String,
        timestamp: Long? = null,
        silent: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        kotlin.concurrent.thread {
            val ok = sendEventSync(context, type, incomingNumber, content, timestamp, silent)
            if (!ok && type == "SMS") {
                // Queue để retry sau
                FailedSmsQueue.enqueue(context, incomingNumber, content, timestamp ?: System.currentTimeMillis())
            }
            onComplete?.invoke()
        }
    }
}