package com.example.informer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat

object SmsInboxSync {
    private const val TAG = "SMS_INBOX_SYNC"

    // watchdog in minutes
    private const val WATCHDOG_INTERVAL_MINUTES = 15

    fun pollMissingSms(context: Context, source: String): Int {
        val userManager = context.getSystemService(android.os.UserManager::class.java)
        if (userManager?.isUserUnlocked != true) {
            Log.w(TAG, "[$source] Device locked, skip poll.")
            return 0
        }
        val safeContext = context.createDeviceProtectedStorageContext()
        HistoryScanBaseline.ensureSmsBaseline(safeContext)

        if (!hasReadSmsPermission(safeContext)) {
            Log.w(TAG, "[$source] READ_SMS not granted, skip poll.")
            return 0
        }

        val prefs = safeContext.getSharedPreferences("AppInternalStateV4", Context.MODE_PRIVATE)
        var lastProcessedSmsId = prefs.getLong("last_processed_sms_id", 0L)
        
        // CHỈNH SỬA: Quét dựa trên cả ID và mốc thời gian (trong vòng 30 phút qua) để dự phòng nếu ID bị kẹt
        val timeThreshold = System.currentTimeMillis() - (30 * 60 * 1000L) // 30 phút trước
        Log.d(TAG, "[$source] poll start lastProcessedSmsId=$lastProcessedSmsId timeThreshold=$timeThreshold")

        return try {
            // Quét tin nhắn mới hơn ID cuối cùng HOẶC tin nhắn trong vòng 30 phút qua
            val cursor = safeContext.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date", "_id"),
                "_id > ? OR date >= ?",
                arrayOf(lastProcessedSmsId.toString(), timeThreshold.toString()),
                "_id ASC"
            )

            var sentCount = 0
            cursor?.use {
                while (it.moveToNext()) {
                    val rawNumber = it.getString(0) ?: continue
                    val body = it.getString(1) ?: continue
                    val date = it.getLong(2)
                    val smsId = it.getLong(3)

                    if (smsId <= lastProcessedSmsId) continue

                    val msgKey = "ID_$smsId"
                    if (prefs.contains(msgKey)) {
                        lastProcessedSmsId = smsId
                        prefs.edit().putLong("last_processed_sms_id", lastProcessedSmsId).commit()
                        Log.d(TAG, "[$source] Skip already processed ID=$smsId")
                        continue
                    }

                    prefs.edit().putBoolean(msgKey, true).commit()
                    lastProcessedSmsId = smsId
                    prefs.edit().putLong("last_processed_sms_id", lastProcessedSmsId).commit()

                    val number = DeviceUtils.formatVietnamesePhoneNumber(rawNumber)
                    val name = DeviceUtils.getDisplayName(safeContext, number)
                    Log.d(TAG, "[$source] Backfill SMS ID=$smsId sender=$number time=$date")
                    SmsBatchManager.enqueue(safeContext, name, body, date)
                    sentCount++
                }
            }

            Log.d(TAG, "[$source] poll done sentCount=$sentCount lastProcessedSmsId=$lastProcessedSmsId time=${System.currentTimeMillis()}"); if (sentCount > 0) { MainActivity.addLog("🛰️ [Maintenance] Quét bù hoàn tất: $sentCount SMS mới.") }
            sentCount
        } catch (e: Exception) {
            Log.e(TAG, "[$source] Lỗi: ${e.message}")
            0
        }
    }

    private fun hasReadSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
