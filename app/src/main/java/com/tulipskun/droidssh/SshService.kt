package com.tulipskun.droidssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** ForegroundService ครอบ SSHD + keep alive (wake/wifi lock + sticky). */
class SshService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSsh()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIF_ID, buildNotification("กำลังเปิด SSH..."))
        // รัน start แบบ background thread (กัน ANR)
        // จับ Throwable (รวม Error เช่น ExceptionInInitializerError) กันแอปเด้ง
        Thread {
            try {
                val port = SshServerManager.start(this)
                Prefs(this).recordStartSuccess()
                updateNotification("SSH รันอยู่ :$port")
            } catch (t: Throwable) {
                val fails = Prefs(this).recordStartFailure()
                Log.e(TAG, "start failed", t)
                if (Prefs(this).startBackoffActive()) {
                    updateNotification("หยุดชั่วคราว (ล้มเหลว $fails ครั้งติดกัน) — กดเปิด SSH ใหม่เพื่อลองอีกครั้ง")
                    stopSelf()
                } else {
                    updateNotification("เปิดไม่สำเร็จ: ${t.message}")
                }
            }
        }.start()
        acquireLocks()
        scheduleKeepAlive(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // ถูก swipe ทิ้ง -> ขอ restart
        sendBroadcast(Intent(ACTION_RESTART).setClass(this, RestartReceiver::class.java))
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopSsh()
        releaseLocks()
        isRunning = false
        // ถ้า autoStart เปิดอยู่ ให้ worker ดึงกลับมา
        if (Prefs(this).autoStart) {
            sendBroadcast(Intent(ACTION_RESTART).setClass(this, RestartReceiver::class.java))
        }
        super.onDestroy()
    }

    private fun stopSsh() {
        SshServerManager.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DroidSSH::wifi").apply {
                setReferenceCounted(false)
                if (!isHeld) acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock: ${e.message}")
        }
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidSSH::wake").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L) // 1 ชม. ระบบจะต่ออายุผ่าน worker/service
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wifiLock = null
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Droid-SSH")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "หยุด", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Droid-SSH", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        private const val TAG = "SshService"
        private const val CHANNEL_ID = "droid_ssh"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.tulipskun.droidssh.STOP"
        const val ACTION_RESTART = "com.tulipskun.droidssh.RESTART_SSH"

        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, SshService::class.java)
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) {
                // Android 12+ จำกัด start FGS จาก background -> โยนให้ caller โชว์ notification/เปิดแอป
                Log.w(TAG, "startForegroundService blocked: ${e.message}")
                throw e
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SshService::class.java).setAction(ACTION_STOP))
        }

        fun scheduleKeepAlive(context: Context) {
            val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                "droid-ssh-keepalive",
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
