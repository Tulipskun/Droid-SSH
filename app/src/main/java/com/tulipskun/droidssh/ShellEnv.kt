package com.tulipskun.droidssh

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files

/**
 * Workspace แบบ Termux: HOME ของตัวเอง + env ครบ + rc เริ่มต้น
 * - HOME = <filesDir>/home (เขียนได้เสมอ, ไม่ต้องพึ่ง /sdcard)
 * - ENV -> ~/.droidrc (dash/mksh จะ source ให้ shell interactive อัตโนมัติ)
 */
object ShellEnv {
    const val RC_NAME = ".droidrc"

    fun homeDir(context: Context): File =
        File(context.applicationContext.filesDir, "home").apply { mkdirs() }

    fun build(context: Context, username: String, term: String, extra: Map<String, String>): Array<String> {
        val app = context.applicationContext
        val home = homeDir(app)
        val user = username.ifBlank { "droid" }
        val env = LinkedHashMap<String, String>()
        env["HOME"] = home.absolutePath
        env["USER"] = user
        env["LOGNAME"] = user
        env["SHELL"] = "/system/bin/sh"
        env["TERM"] = term.ifBlank { "xterm-256color" }
        env["LANG"] = "C.UTF-8"
        val sysPath = System.getenv("PATH").orEmpty()
        val homeBin = File(home, "bin").apply { mkdirs() }.absolutePath
        env["PATH"] = if (sysPath.isBlank()) "$homeBin:/system/bin:/system/xbin:/vendor/bin"
            else "$homeBin:$sysPath"
        env["TMPDIR"] = app.cacheDir.absolutePath
        env["ENV"] = File(home, RC_NAME).absolutePath
        // ค่าจาก client (ssh SendEnv / LANG) ให้ override ได้เฉพาะตัวปลอดภัย
        for ((k, v) in extra) {
            if (k == "TERM" || k == "LANG" || k == "COLORTERM" || k.startsWith("LC_")) {
                env[k] = v
            }
        }
        ensureRc(home)
        ensureStorageLink(home)
        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    /** ~/storage -> /sdcard แบบ Termux (best-effort: เข้าถึงได้จริงเมื่อแอปได้สิทธิ์ไฟล์) */
    private fun ensureStorageLink(home: File) {
        try {
            val sdcard = File("/sdcard")
            if (!sdcard.exists()) return
            val link = File(home, "storage")
            if (link.exists() || Files.isSymbolicLink(link.toPath())) return
            Files.createSymbolicLink(link.toPath(), sdcard.toPath())
        } catch (_: Exception) {
        }
    }

    private fun ensureRc(home: File) {
        try {
            val rc = File(home, RC_NAME)
            if (!rc.exists()) {
                rc.writeText(DEFAULT_RC)
            }
        } catch (e: Exception) {
            Log.w("ShellEnv", "write rc: ${e.message}")
        }
    }

    // หมายเหตุ: ${'$'} คือ escape ของ Kotlin เพื่อให้เหลือ $ ในไฟล์ .droidrc จริง
    private const val DEFAULT_RC = """# Droid-SSH default rc — แก้ได้ตามใจ, อัปเกรดแอปไม่เขียนทับ
HOSTNAME=${'$'}(hostname 2>/dev/null || echo android)
PS1='${'$'}USER@${'$'}HOSTNAME:${'$'}PWD ${'$'} '
alias ll='ls -l'
alias la='ls -la'
echo "Droid-SSH · user=${'$'}USER home=${'$'}HOME · 'exit' to disconnect"
"""
}
