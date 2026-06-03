package com.example.informer

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

object ServerReporter {

    private const val SERVER_API_URL = "https://portal-mirroring.onrender.com/api/push"

    fun sendEvent(context: Context, type: String, incomingNumber: String, content: String) {
        val sharedPref = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val myPhoneNumber = sharedPref.getString("my_phone", "") ?: ""
        val token = sharedPref.getString("token", "") ?: ""

        // SỬA TẠI ĐÂY: Chỉ chặn nếu SĐT trống. Vẫn cho phép chạy tiếp nếu Token trống
        // để hỗ trợ tính năng đồng bộ xóa mã từ xa lên Server.
        if (myPhoneNumber.isEmpty()) {
            MainActivity.addLog("⚠️ Bỏ qua push: Thiết bị chưa được cấu hình Số điện thoại!")
            Log.w("SERVER_REPORTER", "Chưa cấu hình Số điện thoại")
            return
        }

        thread {
            try {
                val url = URL(SERVER_API_URL)
                val conn = url.openConnection() as HttpURLConnection

                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.connectTimeout = 5000

                val sdf = SimpleDateFormat("HH:mm:ss d/M/yyyy", Locale.getDefault())
                val formattedTime = sdf.format(Date())

                val json = JSONObject().apply {
                    put("myPhoneNumber", myPhoneNumber)
                    put("token", token) // Sẽ truyền chuỗi rỗng "" lên nếu đã bấm nút Xóa mã
                    put("type", type.uppercase().trim())
                    put("incomingNumber", incomingNumber)
                    put("content", content)
                    put("time", formattedTime)
                }

                val body = json.toString().toByteArray(Charsets.UTF_8)
                val os: OutputStream = conn.outputStream
                os.write(body)
                os.flush()
                os.close()

                val responseCode = conn.responseCode
                Log.d("SERVER_REPORTER", "API Response Code: $responseCode")

                if (responseCode == 200) {
                    if (type == "RESET") {
                        MainActivity.addLog("🗑️ Đã đồng bộ xóa Token thành công trên Server!")
                    } else {
                        MainActivity.addLog("✅ Đã đẩy thành công: $type từ số $incomingNumber")
                    }
                } else if (responseCode == 403) {
                    MainActivity.addLog("❌ Server từ chối: Token không hợp lệ!")
                } else {
                    MainActivity.addLog("⚠️ Lỗi Server trả về mã: $responseCode")
                }

                conn.disconnect()

            } catch (e: Exception) {
                Log.e("SERVER_REPORTER", "Không thể kết nối đến máy chủ API", e)
                MainActivity.addLog("❌ Lỗi kết nối mạng: Không thể đồng bộ trạng thái Token.")
            }
        }
    }
}