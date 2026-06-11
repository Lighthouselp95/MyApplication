package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BOOT_RECEIVER", "onReceive action=$action")
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.d("BOOT_RECEIVER", "Bỏ qua action không hỗ trợ: $action")
            return
        }

        Log.d("BOOT_RECEIVER", "🚀 Khôi phục nền sau $action.")
        AppLifecycleManager.restoreIfActivated(context, "boot_$action")
    }
}
