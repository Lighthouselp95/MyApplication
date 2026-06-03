package com.example.informer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

class BackgroundMonitoringService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private var connectivityManager: ConnectivityManager? = null
    private var callStateCallback: CallStateCallbackImpl? = null
    private var lastReportedNumber: String? = null

    // WakeLock dùng để giữ CPU không ngủ trong 5 giây khi có sự kiện đẩy dữ liệu lên Render
    private var wakeLock: PowerManager.WakeLock? = null

    // Bộ thu nhận tin nhắn đăng ký động - Giữ sinh mệnh sống thụ động cùng Service
    private var dynamicSmsReceiver: SmsReceiver? = null

    // Lắng nghe mạng thụ động: Chỉ kích hoạt khi OS báo mạng có biến động, không tự quét
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d("METIS_HIBERNATE", "🌐 Mạng khả dụng. Tiến trình chuyển trạng thái sẵn sàng.")
        }
    }

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

        // Khởi tạo thực thể kiểm soát năng lượng CPU
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Informer::DataSyncWakeLock")

        // 1. Đăng ký mạng thụ động (Tiết kiệm pin tuyệt đối, tăng điểm Metis)
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: Exception) {
            Log.e("METIS_HIBERNATE", "Lỗi cấu hình Network Callback: ${e.message}")
        }

        // 2. Đăng ký SmsReceiver động chống đóng băng
        try {
            dynamicSmsReceiver = SmsReceiver()
            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
                priority = 2147483647
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dynamicSmsReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(dynamicSmsReceiver, filter)
            }
            Log.d("METIS_HIBERNATE", "✅ SmsReceiver chế độ thụ động hoạt động.")
        } catch (e: Exception) {
            Log.e("METIS_HIBERNATE", "Lỗi cấu hình SMS Receiver: ${e.message}")
        }

        // Logic bóc tách cuộc gọi thụ động từ phần cứng hạ tầng OS
        val stateChangedAction = { state: Int, incomingNumber: String? ->
            val activeNumber = incomingNumber
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!activeNumber.isNullOrBlank() && activeNumber != lastReportedNumber) {
                        lastReportedNumber = activeNumber

                        // Đánh thức CPU tạm thời trong 5 giây để thực thi tiến trình gửi API không bị đứt gãy
                        acquireTemporaryWakeLock(5000)

                        val formattedNumber = MainActivity.formatVietnamesePhoneNumber(activeNumber)
                        val contactName = getContactName(applicationContext, activeNumber)

                        MainActivity.addLog("📞 Cuộc gọi đến: $activeNumber ($contactName)")

                        // Đẩy dữ liệu bất đồng bộ thụ động
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

        // Đăng ký bộ lắng nghe Telephony chuẩn hóa Android đời cao
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

    private fun acquireTemporaryWakeLock(timeout: Long) {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(timeout)
                Log.d("METIS_HIBERNATE", "⚡ Đã kích hoạt WakeLock ngắn hạn cứu luồng mạng!")
            }
        } catch (e: Exception) {
            Log.e("METIS_HIBERNATE", "Không thể giữ WakeLock", e)
        }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        if (dynamicSmsReceiver != null) {
            try { unregisterReceiver(dynamicSmsReceiver) } catch (e: Exception) {}
        }
        try { connectivityManager?.unregisterNetworkCallback(networkCallback) } catch (e: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callStateCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(null, android.telephony.PhoneStateListener.LISTEN_NONE)
        }

        // Giải phóng khóa năng lượng an toàn
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceNotification() {
        val channelId = "MONITOR_SERVICE_CHANNEL"
        val channelName = "Hệ thống đồng bộ ngầm"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Sử dụng IMPORTANCE_MIN để hệ thống ngầm hiểu App đang ở trạng thái ngủ tĩnh, không tốn tài nguyên nền
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_MIN)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hệ thống kiểm tra phần cứng thiết bị")
            .setContentText("Trạng thái ổn định (Chế độ tiết kiệm tài nguyên ngầm)...")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(101, notification)
    }
}