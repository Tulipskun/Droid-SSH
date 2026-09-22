package com.tulipskun.droidssh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Auto startup หลัง boot / อัปเดต APK. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val a = intent?.action ?: return
        Log.i(TAG, "boot event: $a")
        val ok = a == Intent.ACTION_BOOT_COMPLETED ||
            a == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
            a == Intent.ACTION_MY_PACKAGE_REPLACED ||
            a == "android.intent.action.QUICKBOOT_POWERON"
        if (!ok) return
        if (!Prefs(context).autoStart) {
            Log.i(TAG, "autoStart disabled, skip")
            return
        }
        if (Prefs(context).userStopped) {
            Log.i(TAG, "user stopped manually, skip autostart")
            return
        }
        if (Prefs(context).startBackoffActive()) {
            Log.i(TAG, "in backoff window, skip autostart")
            return
        }
        // Direct boot: ใช้ device-protected storage ถ้าจำเป็น (prefs ธรรมดาอ่านได้หลัง unlock;
        // ถ้า LOCKED_BOOT_COMPLETED มาก่อน unlock อาจ start ไม่ติด จะมี worker ดึงอีกทีหลัง unlock)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, SshService::class.java))
            } else {
                context.startService(Intent(context, SshService::class.java))
            }
            SshService.scheduleKeepAlive(context)
        } catch (e: Exception) {
            // Android 12+ อาจบล็อก FGS จาก background: ฝาก worker ไว้เตือน/สตาร์ทเมื่อมีโอกาส
            Log.w(TAG, "autostart blocked (Android 12+?): ${e.message}")
            try {
                SshService.scheduleKeepAlive(context)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
