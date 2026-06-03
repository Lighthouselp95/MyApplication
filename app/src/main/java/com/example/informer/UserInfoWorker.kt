package com.example.informer

import android.content.Context
import android.util.Log
import androidx.work.OneTimeWorkRequest
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class UserInfoWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    override fun doWork(): Result {
        Log.d("USER_WORKER", "Executing silent status sync...")

        // Reuse your existing ServerReporter infrastructure
        // This fires a POST request to /api/push inside a background thread
        ServerReporter.sendEvent(
            context = applicationContext,
            type = "PING",          // Specialized type for registration/heartbeat
            incomingNumber = "SYSTEM",
            content = "HEARTBEAT"
        )

        // Schedule the next execution precisely 5 minutes from now
        enqueueNextCheck(applicationContext)

        return Result.success()
    }

    companion object {
        fun enqueueNextCheck(context: Context) {
            val checkRequest = OneTimeWorkRequest.Builder(UserInfoWorker::class.java)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .addTag("SILENT_USER_CHECK")
                .build()

            WorkManager.getInstance(context).enqueue(checkRequest)
        }
    }
}