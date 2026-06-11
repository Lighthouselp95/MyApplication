package com.example.informer

import android.app.NotificationChannel
import android.app.NotificationManager
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

    private lateinit var smsObserver: ContentObserver
    private lateinit var phoneStateReceiver: BroadcastReceiver
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private var phoneReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        Log.d("SERVICE", "BackgroundMonitoringService.onCreate()")
        startForegroundNow()
        
        // Luon lap lich Watchdog khi khoi tao
        ServiceWatchdog.schedule(applicationContext, "service-onCreate")
        
        HistoryScanBaseline.ensureInitialized(applicationContext)
        registerPhoneStateReceiver()
        
        if (hasReadSmsPermission()) {
            registerSmsObserver()
        }
        
        // Quet bu du lieu ngay khi vao
        triggerBackfill("SERVICE_START")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("SERVICE", "BackgroundMonitoringService.onStartCommand()")
        startForegroundNow()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("SERVICE", "BackgroundMonitoringService.onDestroy()")
        try { contentResolver.unregisterContentObserver(smsObserver) } catch (e: Exception) {}
        if (phoneReceiverRegistered) {
            try { unregisterReceiver(phoneStateReceiver) } catch (e: Exception) {}
            phoneReceiverRegistered = false
        }
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLifecycleManager.restoreIfActivated(applicationContext, "task_removed")
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
                manager.createNotificationChannel(NotificationChannel(channelId, "He thong bao ve", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("He thong dang chay")
            .setContentText("Dang giam sat tin nhan va cuoc goi.")
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(101, notification)
        } catch (e: Exception) {
            Log.e("SERVICE", "Error startForeground: " + e.message)
        }
    }
}
