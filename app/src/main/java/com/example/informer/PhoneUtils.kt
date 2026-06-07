package com.example.informer

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
object PhoneUtils {

    fun formatVietnamesePhoneNumber(rawNumber: String?): String {
        if (rawNumber.isNullOrBlank()) return "Không rõ số"
        var cleaned = rawNumber.replace("\\s+".toRegex(), "").replace("-", "")
        val match = Regex("^\\+?[0-9]+").find(cleaned)
        if (match != null) cleaned = match.value
        if (cleaned.startsWith("+84")) cleaned = "0" + cleaned.substring(3)
        else if (cleaned.startsWith("84") && cleaned.length > 9) cleaned = "0" + cleaned.substring(2)
        return cleaned
    }

    fun getContactName(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                Log.d("PHONE_UTILS", "Lookup số: $phoneNumber → count=${it.count}")
                if (it.moveToFirst()) {
                    val name = it.getString(0)
                    Log.d("PHONE_UTILS", "Tìm thấy tên: $name")
                    name
                } else {
                    Log.d("PHONE_UTILS", "Không tìm thấy tên cho số: $phoneNumber")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("PHONE_UTILS", "Lỗi lookup: ${e.message}")
            null
        }
    }

    fun getDisplayName(context: Context, phoneNumber: String): String {
        val name = getContactName(context, phoneNumber)
        return if (!name.isNullOrBlank()) "$name\nSĐT: $phoneNumber" else "SĐT: $phoneNumber"
    }
}