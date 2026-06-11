package com.example.informer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

object DeviceUtils {

    fun formatVietnamesePhoneNumber(rawNumber: String?): String {
        if (rawNumber.isNullOrBlank()) return ""
        var cleaned = rawNumber.replace("\\s+".toRegex(), "").replace("-", "")
        val match = Regex("^\\+?[0-9]+").find(cleaned)
        if (match != null) {
            cleaned = match.value
        }
        if (cleaned.startsWith("+84")) {
            cleaned = "0" + cleaned.substring(3)
        } else if (cleaned.startsWith("84") && cleaned.length > 9) {
            cleaned = "0" + cleaned.substring(2)
        }
        return cleaned
    }

    fun getDevicePhoneNumber(context: Context): String {
        val hasNumbersPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hasStatePermission = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        if (hasNumbersPermission || hasStatePermission) {
            try {
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val activeInfos = subscriptionManager.activeSubscriptionInfoList
                if (!activeInfos.isNullOrEmpty()) {
                    for (info in activeInfos) {
                        val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            subscriptionManager.getPhoneNumber(info.subscriptionId)
                        } else {
                            @Suppress("DEPRECATION")
                            info.number
                        }
                        if (!number.isNullOrEmpty()) {
                            return formatVietnamesePhoneNumber(number)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DeviceUtils", "Lỗi SubscriptionManager", e)
            }

            try {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val number = telephonyManager.line1Number
                if (!number.isNullOrEmpty()) {
                    return formatVietnamesePhoneNumber(number)
                }
            } catch (e: SecurityException) {
                Log.e("DeviceUtils", "Lỗi TelephonyManager", e)
            }
        }
        return ""
    }

    fun getDisplayName(context: Context, number: String?): String {
        if (number.isNullOrBlank()) return "Không có số"
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return number
        }
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else number
            } ?: number
        } catch (e: Exception) {
            number
        }
    }

    fun getRequiredPermissions(): Array<String> {
        return buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(Manifest.permission.READ_PHONE_NUMBERS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun checkMissingPermissions(context: Context): List<String> {
        return getRequiredPermissions().filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun openAppBatterySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            context.startActivity(intent)
        }
    }
}
