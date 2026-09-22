package com.tulipskun.droidssh

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/** ตรวจทุก 15 นาที: ถ้าเปิด autoStart แต่ server ดับ -> สตาร์ทใหม่. */
class KeepAliveWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        return         try {
            val prefs = Prefs(applicationContext)
            if (!prefs.autoStart) return Result.success()
            if (prefs.userStopped) return Result.success()
            if (prefs.startBackoffActive()) return Result.success()
            if (!SshServerManager.isRunning) {
                Log.i(TAG, "server down, restarting")
                applicationContext.startForegroundService(Intent(applicationContext, SshService::class.java))
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "keepalive: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "KeepAliveWorker"
    }
}
