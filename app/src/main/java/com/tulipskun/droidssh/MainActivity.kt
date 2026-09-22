package com.tulipskun.droidssh

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as MaterialR

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var tilUser: TextInputLayout
    private lateinit var etUser: TextInputEditText
    private lateinit var etPass: TextInputEditText
    private lateinit var etAuthKeys: TextInputEditText
    private lateinit var swRoot: SwitchMaterial
    private lateinit var swAuto: SwitchMaterial
    private lateinit var swKeyAuth: SwitchMaterial
    private lateinit var statusCard: MaterialCardView
    private lateinit var tvStatus: TextView
    private var lastConnectCmd: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        root = findViewById(R.id.root)
        tilUser = findViewById(R.id.tilUser)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etAuthKeys = findViewById(R.id.etAuthKeys)
        swRoot = findViewById(R.id.swRoot)
        swAuto = findViewById(R.id.swAuto)
        swKeyAuth = findViewById(R.id.swKeyAuth)
        statusCard = findViewById(R.id.statusCard)
        tvStatus = findViewById(R.id.tvStatus)

        loadForm()
        requestNotifPermission()

        // Switch = immediate setting (ตาม M3: ไม่ต้องกด Save ซ้ำ)
        swRoot.setOnCheckedChangeListener { _, checked ->
            prefs.rootMode = checked
            snack("โหมด ${if (checked) "root :22" else "non-root :2222"} — มีผลเมื่อเริ่ม SSH ครั้งถัดไป")
            refreshStatus()
        }
        swAuto.setOnCheckedChangeListener { _, checked ->
            prefs.autoStart = checked
            if (checked) SshService.scheduleKeepAlive(this)
            snack(if (checked) "เปิด auto startup แล้ว" else "ปิด auto startup แล้ว")
        }
        swKeyAuth.setOnCheckedChangeListener { _, checked ->
            prefs.keyAuthEnabled = checked
            snack("key-auth ${if (checked) "เปิด" else "ปิด"} — มีผลเมื่อเริ่ม SSH ครั้งถัดไป")
        }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveForm() }
        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener {
            if (!validateUser()) return@setOnClickListener
            saveCredentials(silent = true)
            try {
                SshService.start(this)
                SshService.scheduleKeepAlive(this)
                snack("กำลังเปิด SSH :${prefs.effectivePort()}")
            } catch (e: Exception) {
                snack("เปิดไม่สำเร็จ (Android 12+ ต้องเปิดแอปค้างไว้): ${e.message}")
            }
            refreshStatus()
        }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            SshService.stop(this)
            // หน่วงนิดให้ service หยุดก่อนรีเฟรช
            tvStatus.postDelayed({ refreshStatus() }, 500)
        }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener { openBatterySettings() }
        findViewById<MaterialButton>(R.id.btnStorage).setOnClickListener { openStorageSettings() }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            if (lastConnectCmd.isEmpty()) {
                snack("ยังไม่มีคำสั่งเชื่อมต่อ (เปิด SSH ก่อน)")
                return@setOnClickListener
            }
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ssh", lastConnectCmd))
            snack("คัดลอกแล้ว: $lastConnectCmd")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun loadForm() {
        etUser.setText(prefs.username)
        etPass.setText("")
        swRoot.isChecked = prefs.rootMode
        swAuto.isChecked = prefs.autoStart
        swKeyAuth.isChecked = prefs.keyAuthEnabled
        val f = prefs.authorizedKeysFile(this)
        etAuthKeys.setText(if (f.exists()) f.readText() else "")
    }

    /** username ว่าง = error inline + focus (recoverable validation) */
    private fun validateUser(): Boolean {
        if (etUser.text.toString().trim().isEmpty()) {
            tilUser.error = "ต้องระบุชื่อผู้ใช้"
            etUser.requestFocus()
            return false
        }
        tilUser.error = null
        return true
    }

    private fun saveForm() {
        if (!validateUser()) return
        saveCredentials(silent = false)
        refreshStatus()
    }

    /** บันทึก username/password/keys (transaction เดียว) */
    private fun saveCredentials(silent: Boolean) {
        prefs.username = etUser.text.toString().trim()
        if (etPass.text.toString().isNotEmpty()) prefs.setPassword(etPass.text.toString())
        etPass.setText("")
        val f = prefs.authorizedKeysFile(this)
        f.parentFile?.mkdirs()
        f.writeText(etAuthKeys.text.toString().trim() + "\n")
        if (!silent) snack("บันทึกแล้ว (port ${prefs.effectivePort()})")
    }

    private fun refreshStatus() {
        val running = SshServerManager.isRunning
        val port = if (running) SshServerManager.runningPort else prefs.effectivePort()
        val mode = if (prefs.rootMode) "root (:22)" else "non-root (:2222)"
        val user = prefs.username.ifBlank { "droid" }
        val ips = NetUtils.getDeviceIps(this).take(3)
        val ipLine = if (ips.isEmpty()) "<ต่อ Wi-Fi ก่อน>" else ips.joinToString(" · ")
        lastConnectCmd = if (ips.isEmpty()) "" else "ssh $user@${ips[0]} -p $port"
        tvStatus.text = buildString {
            append(if (running) "RUNNING :$port" else "STOPPED")
            append("\nโหมด $mode · ผู้ใช้ $user")
            append("\nIP: $ipLine")
            if (lastConnectCmd.isNotEmpty()) append("\n$lastConnectCmd")
            append("\nSFTP เปิด · key-auth ${if (prefs.keyAuthEnabled) "เปิด" else "ปิด"}")
        }
        // semantic roles: running = primaryContainer, stopped = surfaceVariant
        val bgAttr: Int = if (running) MaterialR.attr.colorPrimaryContainer
            else MaterialR.attr.colorSurfaceVariant
        val fgAttr: Int = if (running) MaterialR.attr.colorOnPrimaryContainer
            else MaterialR.attr.colorOnSurfaceVariant
        statusCard.setCardBackgroundColor(MaterialColors.getColor(statusCard, bgAttr))
        tvStatus.setTextColor(MaterialColors.getColor(tvStatus, fgAttr))
        statusCard.contentDescription =
            if (running) "เซิร์ฟเวอร์กำลังทำงาน port $port" else "เซิร์ฟเวอร์หยุดทำงาน"
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
                snack("มีสิทธิ์ไฟล์แล้ว หรือไม่จำเป็นบนเวอร์ชันนี้")
            }
        } catch (e: Exception) {
            snack(e.message ?: "เปิดตั้งค่าไม่ได้")
        }
    }

    private fun snack(msg: String) =
        Snackbar.make(root, msg, Snackbar.LENGTH_SHORT).show()
}
