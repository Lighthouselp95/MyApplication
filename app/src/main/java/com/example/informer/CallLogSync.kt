package com.example.informer

import android.content.Context
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat

object CallLogSync {
    private const val TAG = "CALL_LOG_SYNC"

    fun pollMissingCalls(context: Context, source: String): Int {
        val userManager = context.getSystemService(android.os.UserManager::class.java)
        if (userManager?.isUserUnlocked != true) {
            Log.w(TAG, "[$source] Device locked, skip poll.")
            return 0
        }
        val safeContext = context.deviceProtectedContext()
        HistoryScanBaseline.ensureCallBaseline(safeContext)

        if (!hasReadCallLogPermission(safeContext)) {
            Log.w(TAG, "[$source] READ_CALL_LOG not granted, skip poll.")
            return 0
        }

        val prefs = safeContext.getSharedPreferences("AppInternalStateV4", Context.MODE_PRIVATE)
        var lastProcessedCallId = prefs.getLong("last_processed_call_log_id", 0L)
        Log.d(TAG, "[$source] poll start lastProcessedCallId=$lastProcessedCallId time=${System.currentTimeMillis()}")

        return try {
            val cursor = safeContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION
                ),
                "${CallLog.Calls._ID} > ?",
                arrayOf(lastProcessedCallId.toString()),
                "${CallLog.Calls._ID} ASC"
            )

            var sentCount = 0
            cursor?.use {
                while (it.moveToNext()) {
                    val callId = it.getLong(0)
                    val rawNumber = it.getString(1) ?: ""
                    val date = it.getLong(2)
                    val type = it.getInt(3)
                    val durationSec = it.getLong(4)

                    if (callId <= lastProcessedCallId) continue

                    val dedupeKey = "CALLLOG_ID_$callId"
                    if (prefs.contains(dedupeKey)) {
                        lastProcessedCallId = callId
                        prefs.edit().putLong("last_processed_call_log_id", lastProcessedCallId).commit()
                        Log.d(TAG, "[$source] Skip already processed callId=$callId")
                        continue
                    }

                    prefs.edit().putBoolean(dedupeKey, true).commit()
                    lastProcessedCallId = callId
                    prefs.edit().putLong("last_processed_call_log_id", lastProcessedCallId).commit()

                    if (type != CallLog.Calls.MISSED_TYPE) {
                        continue
                    }

                    val formattedNumber = DeviceUtils.formatVietnamesePhoneNumber(rawNumber)
                    val name = if (formattedNumber.isBlank()) {
                        "Không rõ số"
                    } else {
                        DeviceUtils.getDisplayName(safeContext, formattedNumber)
                    }
                    val content = "Cuộc gọi nhỡ${if (durationSec > 0) " - thời lượng ${durationSec}s" else ""}"
                    Log.d(TAG, "[$source] Backfill missed call ID=$callId number=$formattedNumber time=$date")
                    val ok = ServerReporter.sendEventSync(safeContext, "CALL", name, content, date)
                    if (ok) sentCount++ else Log.w(TAG, "[$source] sendEventSync failed for CALL ID=$callId")
                }
            }

            Log.d(TAG, "[$source] poll done sentCount=$sentCount lastProcessedCallId=$lastProcessedCallId time=${System.currentTimeMillis()}"); if (sentCount > 0) { MainActivity.addLog("🛰️ [Maintenance] Quét bù hoàn tất: $sentCount Cuộc gọi nhỡ mới.") }
            sentCount
        } catch (e: Exception) {
            Log.e(TAG, "[$source] Lỗi: ${e.message}")
            0
        }
    }

    private fun hasReadCallLogPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALL_LOG
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
