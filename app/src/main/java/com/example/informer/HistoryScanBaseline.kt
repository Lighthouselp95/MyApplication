package com.example.informer

import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat

object HistoryScanBaseline {
    private const val PREF_NAME = "AppInternalStateV4"
    private const val KEY_SMS_READY = "sms_scan_baseline_ready"
    private const val KEY_CALL_READY = "call_scan_baseline_ready"
    private const val KEY_LAST_SMS_ID = "last_processed_sms_id"
    private const val KEY_LAST_CALL_ID = "last_processed_call_log_id"
    private const val TAG = "SCAN_BASELINE"

    fun ensureInitialized(context: Context): Boolean {
        val safeContext = context.deviceProtectedContext()
        val smsReady = ensureSmsBaseline(safeContext)
        val callReady = ensureCallBaseline(safeContext)
        return smsReady || callReady
    }

    fun ensureSmsBaseline(context: Context): Boolean {
        if (!hasReadSmsPermission(context)) {
            return false
        }

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SMS_READY, false)) return false

        val installTime = installTimeMs(context)
        val anchorId = latestSmsIdAtOrBefore(context, installTime) ?: 0L

        prefs.edit()
            .putLong(KEY_LAST_SMS_ID, anchorId)
            .putBoolean(KEY_SMS_READY, true)
            .commit()

        Log.d(TAG, "Initialized SMS baseline installTime=$installTime anchorId=$anchorId")
        return true
    }

    fun ensureCallBaseline(context: Context): Boolean {
        if (!hasReadCallLogPermission(context)) {
            return false
        }

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CALL_READY, false)) return false

        val installTime = installTimeMs(context)
        val anchorId = latestCallIdAtOrBefore(context, installTime) ?: 0L

        prefs.edit()
            .putLong(KEY_LAST_CALL_ID, anchorId)
            .putBoolean(KEY_CALL_READY, true)
            .commit()

        Log.d(TAG, "Initialized CALL baseline installTime=$installTime anchorId=$anchorId")
        return true
    }

    private fun latestSmsIdAtOrBefore(context: Context, cutoffMs: Long): Long? {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id"),
                "date <= ?",
                arrayOf(cutoffMs.toString()),
                "date DESC, _id DESC"
            )
            cursor?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (e: Exception) {
            Log.e(TAG, "latestSmsIdAtOrBefore failed: ${e.message}")
            null
        }
    }

    private fun latestCallIdAtOrBefore(context: Context, cutoffMs: Long): Long? {
        return try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                "${CallLog.Calls.DATE} <= ?",
                arrayOf(cutoffMs.toString()),
                "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC"
            )
            cursor?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (e: Exception) {
            Log.e(TAG, "latestCallIdAtOrBefore failed: ${e.message}")
            null
        }
    }

    private fun installTimeMs(context: Context): Long {
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        } catch (e: Exception) {
            Log.e(TAG, "installTime lookup failed: ${e.message}")
            System.currentTimeMillis()
        }
    }

    private fun hasReadSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasReadCallLogPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALL_LOG
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
