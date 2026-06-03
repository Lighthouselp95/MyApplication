package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class PhoneReceiver : BroadcastReceiver() {

    companion object {
        // Biến static lưu lại số cuối cùng được xử lý để chống trùng lặp trên diện rộng
        private var lastProcessedNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // 1. CHẶN ĐỨNG TIN HIỆU RỖNG: Nếu chưa có số thật, hủy luồng ngay lập tức.
                    // Không cho phép biến số null thành "Số ẩn/Không rõ" để gửi bậy lên Server nữa.
                    if (incomingNumber.isNullOrBlank()) {
                        MainActivity.addLog("📞 [Receiver] Nhận tín hiệu mồi rỗng -> Bỏ qua, đợi Service xử lý số thật...")
                        return
                    }

                    // 2. CHỐNG TRÙNG LẶP TIẾP THEO: Nếu số này vừa mới kích hoạt rồi, chặn lại
                    if (incomingNumber == lastProcessedNumber) {
                        return
                    }

                    // Ghi nhớ số điện thoại đang xử lý
                    lastProcessedNumber = incomingNumber
                    MainActivity.addLog("📞 [Receiver] Phát hiện cuộc gọi hợp lệ từ: $incomingNumber")

                    // Gọi ServerReporter gửi sự kiện chuẩn lên mây
                    ServerReporter.sendEvent(
                        context = context,
                        type = "CALL",
                        incomingNumber = incomingNumber,
                        content = "Cuộc gọi đến đang đổ chuông..."
                    )
                }

                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // Khi thiết bị dập máy hoặc trở về trạng thái chờ, giải phóng khóa chặn
                    lastProcessedNumber = null
                }
            }
        }
    }
}