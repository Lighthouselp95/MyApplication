package com.example.informer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat

class BackgroundMonitoringService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private var callStateCallback: CallStateCallbackImpl? = null
    private var lastReportedNumber: String? = null

    // Định nghĩa bộ lắng nghe Callback theo chuẩn Modern Android
    private class CallStateCallbackImpl(
        private val onCallStateChangedAction: (state: Int, incomingNumber: String?) -> Unit
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            onCallStateChangedAction(state, null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // Logic xử lý cuộc gọi kèm tra cứu danh bạ
        val stateChangedAction = { state: Int, incomingNumber: String? ->
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    if (incomingNumber.isNullOrBlank()) {
                        MainActivity.addLog("📱 Hệ thống nhận tín hiệu mồi (Đang chờ bóc tách số)...")
                    } else if (incomingNumber != lastReportedNumber) {
                        lastReportedNumber = incomingNumber

                        // 🔍 TRA CỨU TÊN TRONG DANH BẠ
                        val contactName = getContactName(applicationContext, incomingNumber)

                        MainActivity.addLog("📞 Bắt được cuộc gọi: $incomingNumber ($contactName)")

                        // Đẩy dữ liệu đầy đủ Tên + Số lên Server Render
                        ServerReporter.sendEvent(
                            context = applicationContext,
                            type = "CALL",
                            incomingNumber = incomingNumber,
                            // Đính kèm thông tin tên người gọi vào phần nội dung để Frontend hiển thị lý tưởng nhất
                            content = "Cuộc gọi đến từ: $contactName"
                        )
                    }
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    MainActivity.addLog("📞 Cuộc gọi đã được nhấc máy trả lời")
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    MainActivity.addLog("📱 Thiết bị trở về trạng thái chờ (Idle)")
                    lastReportedNumber = null
                }
            }
        }

        // Đăng ký bộ lắng nghe
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callStateCallback = CallStateCallbackImpl(stateChangedAction)
            callStateCallback?.let {
                telephonyManager.registerTelephonyCallback(mainExecutor, it)
            }
        } else {
            @Suppress("DEPRECATION")
            val legacyListener = object : android.telephony.PhoneStateListener() {
                override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                    stateChangedAction(state, incomingNumber)
                }
            }
            telephonyManager.listen(legacyListener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    /**
     * HÀM PHỤ TRỢ: Quét bộ nhớ danh bạ điện thoại bằng số Phone phát hiện được
     */
    private fun getContactName(context: Context, phoneNumber: String): String {
        var contactName = "Số lạ (Chưa lưu)"
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        // Các cột thông tin cần bốc tách (ở đây ta chỉ lấy tên hiển thị DISPLAY_NAME)
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        try {
            context.contentResolver.query(uri, projection, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        contactName = cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            contactName = "Số máy chưa phân quyền danh bạ"
        }
        return contactName
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callStateCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(null, android.telephony.PhoneStateListener.LISTEN_NONE)
        }
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    private fun startForegroundServiceNotification() {
        val channelId = "MONITOR_SERVICE_CHANNEL"
        val channelName = "Hệ thống đồng bộ ngầm"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hệ thống giám sát đang chạy ngầm")
            .setContentText("Thiết bị đang kết nối và chờ đồng bộ...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(101, notification)
    }
}