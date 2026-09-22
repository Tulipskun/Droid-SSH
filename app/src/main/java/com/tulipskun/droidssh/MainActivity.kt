package com.tulipskun.droidssh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etAuthKeys: EditText
    private lateinit var swRoot: Switch
    private lateinit var swAuto: Switch
    private lateinit var swKeyAuth: Switch
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etAuthKeys = findViewById(R.id.etAuthKeys)
        swRoot = findViewById(R.id.swRoot)
        swAuto = findViewById(R.id.swAuto)
        swKeyAuth = findViewById(R.id.swKeyAuth)
        tvStatus = findViewById(R.id.tvStatus)
        val btnStart: Button = findViewById(R.id.btnStart)
        val btnStop: Button = findViewById(R.id.btnStop)
        val btnSave: Button = findViewById(R.id.btnSave)
        val btnBattery: Button = findViewById(R.id.btnBattery)
        val btnStorage: Button = findViewById(R.id.btnStorage)

        loadForm()
        requestNotifPermission()

        btnSave.setOnClickListener { saveForm() }
        btnStart.setOnClickListener {
            saveForm()
            try {
                SshService.start(this)
                SshService.scheduleKeepAlive(this)
                toast("กำลังเปิด SSH :${prefs.effectivePort()}")
            } catch (e: Exception) {
                toast("เปิดไม่สำเร็จ (Android 12+ ต้องเปิดแอปค้างไว้): ${e.message}")
            }
            refreshStatus()
        }
        btnStop.setOnClickListener {
            SshService.stop(this)
            refreshStatus()
        }
        btnBattery.setOnClickListener { openBatterySettings() }
        btnStorage.setOnClickListener { openStorageSettings() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun loadForm() {
        etUser.setText(prefs.username)
        etPass.setText("") // hash แล้วแสดงคืนไม่ได้: ว่าง = ไม่เปลี่ยน
        etPass.hint = if (prefs.hasCustomPassword()) "รหัสผ่านใหม่ (ว่าง = ไม่เปลี่ยน)" else "รหัสผ่าน (default: droid)"
        swRoot.isChecked = prefs.rootMode
        swAuto.isChecked = prefs.autoStart
        swKeyAuth.isChecked = prefs.keyAuthEnabled
        val f = prefs.authorizedKeysFile(this)
        etAuthKeys.setText(if (f.exists()) f.readText() else "")
    }

    private fun saveForm() {
        prefs.username = etUser.text.toString().trim().ifEmpty { "droid" }
        if (etPass.text.isNotEmpty()) prefs.setPassword(etPass.text.toString())
        etPass.setText("")
        prefs.rootMode = swRoot.isChecked
        prefs.autoStart = swAuto.isChecked
        prefs.keyAuthEnabled = swKeyAuth.isChecked
        val f = prefs.authorizedKeysFile(this)
        f.parentFile?.mkdirs()
        f.writeText(etAuthKeys.text.toString().trim() + "\n")
        if (prefs.autoStart) SshService.scheduleKeepAlive(this)
        toast("บันทึกแล้ว (port ${prefs.effectivePort()})")
        refreshStatus()
    }

    private fun refreshStatus() {
        val running = SshServerManager.isRunning
        val port = if (running) SshServerManager.runningPort else prefs.effectivePort()
        val mode = if (prefs.rootMode) "root (22)" else "non-root (2222)"
        tvStatus.text = buildString {
            append("สถานะ: ${if (running) "RUNNING :$port" else "STOPPED"} \n")
            append("โหมด: $mode | user: ${prefs.username}\n")
            append("SFTP: เปิด | key-auth: ${if (prefs.keyAuthEnabled) "เปิด" else "ปิด"}\n")
            append("เชื่อมต่อ: ssh ${prefs.username}@<ip> -p $port")
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openStorageSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
            ) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                toast("มีสิทธิ์ไฟล์แล้ว หรือไม่จำเป็นบนเวอร์ชันนี้")
            }
        } catch (e: Exception) {
            toast(e.message ?: "เปิดตั้งค่าไม่ได้")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
