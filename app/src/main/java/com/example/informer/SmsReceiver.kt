package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("SMS_RECEIVER", "📡 Tín hiệu Broadcast đổ vào SmsReceiver. Action: ${intent.action}")

        // Trích xuất trực tiếp mảng dữ liệu tin nhắn đi kèm trong Intent
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        if (!messages.isNullOrEmpty()) {
            val firstMessage = messages[0]
            val rawIncomingNumber = firstMessage.originatingAddress ?: "Không rõ số"
            val formattedNumber = PhoneUtils.formatVietnamesePhoneNumber(rawIncomingNumber)
            // Gộp toàn bộ nội dung nếu tin nhắn dài bị chia làm nhiều phần
            val fullContent = messages.joinToString(separator = "") { it.messageBody ?: "" }

            MainActivity.addLog("💬 [Receiver] Nhận tin nhắn từ $formattedNumber")
            Log.d("SMS_RECEIVER", "Nội dung nhận được: $fullContent")

            // Đẩy thông tin thực tế sang ServerReporter
            ServerReporter.sendEvent(
                context = context,
                type = "SMS",
                incomingNumber = formattedNumber,
                content = fullContent
            )
        } else {
            Log.w("SMS_RECEIVER", "⚠ Nhận được Broadcast nhưng mảng dữ liệu tin nhắn trống.")
        }
    }
}