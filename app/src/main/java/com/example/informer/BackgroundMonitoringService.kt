package com.example.informer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class BackgroundMonitoringService : Service() {

    private lateinit var screenReceiver: BroadcastReceiver
    private lateinit var smsObserver: ContentObserver
    private lateinit var phoneStateReceiver: BroadcastReceiver
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private var phoneReceiverRegistered = false
    private var isPollingActive = false
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        Log.d("SERVICE", "BackgroundMonitoringService.onCreate()")
        startForegroundNow()
        
        val prefs = applicationContext.getSharedPreferences("ServiceState", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_service_running", true).apply()
        
        registerScreenStateReceiver()
        HistoryScanBaseline.ensureInitialized(applicationContext)
        registerPhoneStateReceiver()
        checkAndSetupSystemObservers("SERVICE_START")
    }

    private fun checkAndSetupSystemObservers(source: String) {
        val userManager = getSystemService(android.os.UserManager::class.java)
        if (userManager?.isUserUnlocked == true) {
            Log.d("SERVICE", "Device unlocked, starting observers.")

            if (hasReadSmsPermission()) {
                registerSmsObserver()
            }
            startPolling()
        } else {
            Log.w("SERVICE", "Device still locked, deferring observers.")
            Handler(Looper.getMainLooper()).postDelayed({
                checkAndSetupSystemObservers(source)
            }, 5000)
        }
    }

    private fun registerScreenStateReceiver() {
        screenReceiver = ScreenStateReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
        Log.d("SERVICE", "✅ Đã đăng ký ScreenStateReceiver")
    }

    private fun startPolling() {
        if (isPollingActive) return
        isPollingActive = true
        Log.d("SERVICE", "startPolling: Khởi tạo nhịp tim Alarm đầu tiên (1 phút)")
        triggerBackfill("INITIAL_POLLING")
        scheduleNextAlarm(60_000L)
    }

    private fun scheduleNextAlarm(delayMs: Long) {
        val alarmIntent = Intent(applicationContext, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs,
                    pendingIntent
                )
            } catch (e: SecurityException) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs,
                    pendingIntent
                )
            } catch (e: Exception) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                pendingIntent
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("SERVICE", "BackgroundMonitoringService.onStartCommand() intentIsNull=${intent == null}")
        if (!foregroundStarted) startForegroundNow()
        if (intent == null) {
            checkAndSetupSystemObservers("RESTART_STICKY")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("SERVICE", "BackgroundMonitoringService.onDestroy()")
        val prefs = applicationContext.getSharedPreferences("ServiceState", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_service_running", false).apply()

        try { contentResolver.unregisterContentObserver(smsObserver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        if (phoneReceiverRegistered) {
            try { unregisterReceiver(phoneStateReceiver) } catch (_: Exception) {}
            phoneReceiverRegistered = false
        }
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLifecycleManager.ensureBackgroundRunning(applicationContext, "task_removed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerPhoneStateReceiver() {
        if (phoneReceiverRegistered) return
        phoneStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pendingResult = goAsync()
                CallEventHandler.handleIncomingCall(context, intent, "SERVICE", pendingResult)
            }
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        ContextCompat.registerReceiver(this, phoneStateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        phoneReceiverRegistered = true
    }

    private fun registerSmsObserver() {
        smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                backgroundExecutor.execute { SmsInboxSync.pollMissingSms(applicationContext, "OBSERVER") }
            }
        }
        contentResolver.registerContentObserver(Uri.parse("content://sms/inbox"), true, smsObserver)
        Log.d("SMS_OBSERVER", "✅ Đã đăng ký ContentObserver thành công")
    }

    private fun triggerBackfill(source: String) {
        backgroundExecutor.execute {
            SmsInboxSync.pollMissingSms(applicationContext, source)
            CallLogSync.pollMissingCalls(applicationContext, source)
        }
    }

    private fun hasReadSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun startForegroundNow() {
        val channelId = "MONITOR_CHANNEL_V5"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(channelId, "Dịch vụ nền", NotificationManager.IMPORTANCE_MIN)
                channel.description = "Dịch vụ đang giám sát tin nhắn"
                channel.setSound(null, null)
                channel.enableLights(false)
                channel.enableVibration(false)
                manager.createNotificationChannel(channel)
            }
        }

        // Kiểm tra POST_NOTIFICATIONS trên Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w("SERVICE", "Thiếu quyền POST_NOTIFICATIONS, không thể start foreground. Service sẽ chạy background tạm thời.")
                foregroundStarted = false
                return
            }
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Hệ thống đang hoạt động")
            .setContentText("Đang giám sát ngầm")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(101, notification)
            }
            foregroundStarted = true
        } catch (e: Exception) {
            Log.e("SERVICE", "Error startForeground: ${e.message}")
            foregroundStarted = false
        }
    }
}