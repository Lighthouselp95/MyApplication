package com.example.informer

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

class KeepAliveJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val context = applicationContext
        val prefs = context.getSharedPreferences("ServiceState", 0)
        
        // Kiểm tra AM còn sống không (heartbeat trong 30 phút gần đây)
        // AM ping mỗi 1-5 phút (screen on) hoặc 3-5 phút (screen off), 30 phút là ngưỡng an toàn
        val lastHeartbeat = prefs.getLong("last_alarm_heartbeat", 0L)
        val amAlive = (System.currentTimeMillis() - lastHeartbeat) < 30 * 60 * 1000L
        
        if (amAlive) {
            Log.d("KEEP_ALIVE", "⏺ AM còn sống, JobScheduler bỏ qua lượt này.")
            jobFinished(params, false)
            return true
        }
        
        Log.d("KEEP_ALIVE", "🆘 AM đã chết, JobScheduler kích hoạt hồi sinh...")
        AppLifecycleManager.ensureBackgroundRunning(context, "JOB_SCHEDULER")

        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // Return true để hệ thống tự động chạy lại job này nếu bị stop giữa chừng
        return true
    }
}
