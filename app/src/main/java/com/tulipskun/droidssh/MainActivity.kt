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
import androidx.activity.enableEdgeToEdge
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
    private lateinit var tvGuest: TextView
    private lateinit var btnGuest: MaterialButton
    private var lastConnectCmd: String = ""
    // กัน listener วนซ้ำตอนกด "เลิกทำ" (setChecked โปรแกรมมาติก)
    private var suppressSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // insets จัดการผ่าน android:fitsSystemWindows ใน layout
        // (CoordinatorLayout/AppBarLayout/NestedScrollView จัดการเอง)
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
        tvGuest = findViewById(R.id.tvGuest)
        btnGuest = findViewById(R.id.btnGuest)

        loadForm()
        requestNotifPermission()

        // Switch = immediate setting แต่มี "เลิกทำ" ทุกครั้ง
        // (สวิตช์เต็มความกว้างจอ แตะโดนตอนเลื่อนได้ง่าย — ต้องกู้คืนได้ใน 1 แตะ)
        swRoot.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            applyRoot(checked, showUndo = true)
        }
        swAuto.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            applyAuto(checked, showUndo = true)
        }
        swKeyAuth.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            applyKeyAuth(checked, showUndo = true)
        }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveForm() }
        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener {
            if (!validateUser()) return@setOnClickListener
            saveCredentials(silent = true)
            try {
                SshService.start(this)
                SshService.scheduleKeepAlive(this)
                snack("กำลังเปิด SSH บนพอร์ต ${prefs.effectivePort()}")
            } catch (e: Exception) {
                snack("เปิดไม่สำเร็จ (Android 12 ขึ้นไปต้องเปิดแอปค้างไว้): ${e.message}")
            }
            refreshStatus()
        }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            SshService.stop(this)
            snack("หยุด SSH แล้ว")
            // หน่วงนิดให้ service หยุดก่อนรีเฟรช
            tvStatus.postDelayed({ refreshStatus() }, 500)
        }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener { openBatterySettings() }
        findViewById<MaterialButton>(R.id.btnStorage).setOnClickListener { openStorageSettings() }
        refreshGuest()
        btnGuest.setOnClickListener { installGuest() }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            if (lastConnectCmd.isEmpty()) {
                snack("ยังไม่มีคำสั่งเชื่อมต่อ (เปิด SSH ก่อน)")
                return@setOnClickListener
            }
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ssh", lastConnectCmd))
            snack("คัดลอกคำสั่งแล้ว")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshGuest()
    }

    private fun setSwitchSilent(sw: SwitchMaterial, checked: Boolean) {
        suppressSwitch = true
        sw.isChecked = checked
        suppressSwitch = false
    }

    private fun applyRoot(checked: Boolean, showUndo: Boolean) {
        prefs.rootMode = checked
        refreshStatus()
        val msg = "สลับเป็น${if (checked) "โหมดรูท (:22)" else "โหมดทั่วไป (:2222)"} — มีผลครั้งถัดไปที่เปิด SSH"
        if (!showUndo) {
            snack(msg)
            return
        }
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG).setAction("เลิกทำ") {
            setSwitchSilent(swRoot, !checked)
            applyRoot(!checked, showUndo = false)
        }.show()
    }

    private fun applyAuto(checked: Boolean, showUndo: Boolean) {
        prefs.autoStart = checked
        if (checked) SshService.scheduleKeepAlive(this)
        val msg = if (checked) "เปิดการเริ่มอัตโนมัติแล้ว" else "ปิดการเริ่มอัตโนมัติแล้ว"
        if (!showUndo) {
            snack(msg)
            return
        }
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG).setAction("เลิกทำ") {
            setSwitchSilent(swAuto, !checked)
            applyAuto(!checked, showUndo = false)
        }.show()
    }

    private fun applyKeyAuth(checked: Boolean, showUndo: Boolean) {
        prefs.keyAuthEnabled = checked
        val msg = "ล็อกอินด้วยคีย์${if (checked) "เปิดแล้ว" else "ปิดแล้ว"} — มีผลครั้งถัดไป"
        if (!showUndo) {
            snack(msg)
            return
        }
        Snackbar.make(root, msg, Snackbar.LENGTH_LONG).setAction("เลิกทำ") {
            setSwitchSilent(swKeyAuth, !checked)
            applyKeyAuth(!checked, showUndo = false)
        }.show()
    }

    private fun refreshGuest() {
        val base = GuestManager.statusText(this)
        tvGuest.text = if (!GuestManager.isInstalled(this) &&
            GuestManager.supportedAbi() != null && !ShellEnv.suAvailable()
        ) {
            base + "\nต้องใช้ root (สำหรับ mount) — อนุญาตรูทให้แอปก่อน"
        } else {
            base
        }
        btnGuest.isEnabled = !GuestManager.isInstalled(this) && GuestManager.supportedAbi() != null
    }

    /** ติดตั้ง Debian guest (โหลด ~65MB) — รันนอก main thread พร้อม progress */
    private fun installGuest() {
        if (!ShellEnv.suAvailable()) {
            snack("guest ต้องใช้ root (mount) — อนุญาตรูทใน KernelSU ก่อน")
            return
        }
        btnGuest.isEnabled = false
        tvGuest.text = "กำลังเริ่มดาวน์โหลด..."
        Thread({
            val r = GuestManager.install(this) { done, total ->
                runOnUiThread {
                    tvGuest.text = if (total > 0) {
                        "กำลังดาวน์โหลด ${done / 1024 / 1024} / ${total / 1024 / 1024} MB"
                    } else {
                        "กำลังดาวน์โหลด ${done / 1024 / 1024} MB"
                    }
                }
            }
            runOnUiThread {
                r.onSuccess {
                    val mounted = GuestManager.ensureMounts(this)
                    refreshGuest()
                    snack(
                        if (mounted) "ติดตั้งเสร็จ — พิมพ์ dlogin ใน SSH เพื่อเข้า Debian"
                        else "ติดตั้งเสร็จ แต่ mount ไม่ครบ — ลองกดเปิด SSH ใหม่อีกครั้ง"
                    )
                }.onFailure { e ->
                    refreshGuest()
                    snack("ติดตั้งไม่สำเร็จ: ${e.message}")
                }
            }
        }, "guest-install").start()
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
            tilUser.error = "กรุณาระบุชื่อผู้ใช้"
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
    }    /** บันทึก username/password/keys (transaction เดียว) */
    private fun saveCredentials(silent: Boolean) {
        prefs.username = etUser.text.toString().trim()
        if (etPass.text.toString().isNotEmpty()) prefs.setPassword(etPass.text.toString())
        etPass.setText("")
        val f = prefs.authorizedKeysFile(this)
        f.parentFile?.mkdirs()
        f.writeText(etAuthKeys.text.toString().trim() + "\n")
        if (!silent) snack("บันทึกแล้ว (พอร์ต ${prefs.effectivePort()})")
    }

    private fun refreshStatus() {
        val running = SshServerManager.isRunning
        val port = if (running) SshServerManager.runningPort else prefs.effectivePort()
        val mode = if (prefs.rootMode) {
            if (ShellEnv.suAvailable()) "โหมดรูท (พอร์ต 22, พร้อมใช้ su)" else "โหมดรูท (พอร์ต 22, รออนุญาตรูท)"
        } else {
            "โหมดทั่วไป (พอร์ต 2222)"
        }
        val user = prefs.username.ifBlank { "droid" }
        val ips = NetUtils.getDeviceIps(this).take(3)
        val ipLine = if (ips.isEmpty()) "<เชื่อมต่อ Wi-Fi ก่อน>" else ips.joinToString(" · ")
        lastConnectCmd = if (ips.isEmpty()) "" else "ssh $user@${ips[0]} -p $port"
        tvStatus.text = buildString {
            append(if (running) "กำลังทำงาน :$port" else "หยุดทำงาน")
            if (!running && prefs.userStopped) append(" (คุณกดหยุดไว้)")
            append("\n$mode · ผู้ใช้ $user")
            append("\nIP: $ipLine")
            if (lastConnectCmd.isNotEmpty()) append("\n$lastConnectCmd")
            append("\nSFTP: เปิด · ล็อกอินด้วยคีย์: ${if (prefs.keyAuthEnabled) "เปิด" else "ปิด"}")
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
                snack("มีสิทธิ์แล้ว หรือรุ่นนี้ไม่ต้องขอ")
            }
        } catch (e: Exception) {
            snack(e.message ?: "เปิดหน้าตั้งค่าไม่ได้")
        }
    }

    private fun snack(msg: String) =
        Snackbar.make(root, msg, Snackbar.LENGTH_SHORT).show()
}
