package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BOOT_RECEIVER", "onReceive action=$action")

        // Với ACTION_USER_UNLOCKED hoặc BOOT_COMPLETED, đều chạy đầy đủ
        // ensureBackgroundRunning đã check isUserUnlocked bên trong
        AppLifecycleManager.restoreIfActivated(context, action ?: "boot")
    }
}
