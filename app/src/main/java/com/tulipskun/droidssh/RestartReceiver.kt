package com.tulipskun.droidssh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** รับคำสั่ง restart เมื่อ service ถูก kill / swipe ทิ้ง. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Prefs(context).autoStart) return
        if (Prefs(context).userStopped) return
        if (Prefs(context).startBackoffActive()) return
        if (SshService.isRunning && SshServerManager.isRunning) return
        Log.i(TAG, "restarting ssh service")
        try {
            context.startForegroundService(Intent(context, SshService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "restart blocked: ${e.message}")
            SshService.scheduleKeepAlive(context)
        }
    }

    companion object {
        private const val TAG = "RestartReceiver"
    }
}
