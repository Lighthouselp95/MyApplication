package com.example.informer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Lắng nghe sự thay đổi kết nối mạng
 * Khi mạng quay trở lại → retry các SMS trong FailedSmsQueue
 */
class ConnectivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (hasInternet) {
            val pending = FailedSmsQueue.pendingCount(context)
            if (pending > 0) {
                Log.d("CONNECTIVITY", "📡 Mạng quay lại, retry $pending SMS thất bại...")
                val sent = FailedSmsQueue.dequeueAndRetry(context)
                if (sent > 0) {
                    MainActivity.addLog("📡 [Mạng] Đã gửi lại $sent SMS từ hàng đợi")
                }
            }
        }
    }
}