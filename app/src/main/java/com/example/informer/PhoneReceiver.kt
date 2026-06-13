package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PhoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!AppLifecycleManager.isActivated(context)) {
            Log.d("PHONE_RECEIVER", "App chưa kích hoạt, bỏ qua cuộc gọi.")
            return
        }
        Log.d("PHONE_RECEIVER", "Forwarding PHONE_STATE broadcast to CallEventHandler")
        val pendingResult = goAsync()
        CallEventHandler.handleIncomingCall(context, intent, "MANIFEST", pendingResult)
    }
}
