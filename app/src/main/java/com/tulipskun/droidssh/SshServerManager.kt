package com.tulipskun.droidssh

import android.content.Context
import android.util.Log
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.net.BindException
import java.nio.file.Paths
import java.security.PublicKey

/**
 * จัดการ Apache MINA SSHD lifecycle.
 * - รองรับ password (SHA-256+salt) + publickey (RSA/ECDSA/Ed25519) พร้อมกัน
 * - shell + one-shot exec + sftp
 * - port 22 (root) / 2222 (non-root) + fallback อัตโนมัติ
 */
object SshServerManager {
    private const val TAG = "SshServerManager"

    @Volatile private var server: SshServer? = null
    @Volatile private var started: Boolean = false
    @Volatile var runningPort: Int = -1
        private set
    @Volatile var lastError: String? = null
        private set

    val isRunning: Boolean get() = started && server != null

    @Synchronized
    fun start(context: Context): Int {
        if (isRunning) return runningPort
        lastError = null
        val app = context.applicationContext
        val prefs = Prefs(app)

        val sshDir = File(app.filesDir, "ssh").apply { mkdirs() }
        val hostKey = File(sshDir, "hostkey.ser")

        // ลำดับ port ที่จะลอง: ตามโหมดก่อน แล้ว fallback อีก port
        val wanted = prefs.effectivePort()
        val fallback = if (wanted == Prefs.PORT_ROOT) Prefs.PORT_NONROOT else Prefs.PORT_ROOT
        val candidates = listOf(wanted, fallback).distinct()

        var lastEx: Exception? = null
        for (port in candidates) {
            try {
                val s = buildServer(app, prefs, hostKey, port)
                s.start()
                server = s
                started = true
                runningPort = port
                Log.i(TAG, "sshd started on port $port root=${prefs.rootMode}")
                return port
            } catch (e: Exception) {
                // BindException (port 22 ไม่มีสิทธิ์/root ไม่อนุญาต) -> ลอง port ถัดไป
                Log.w(TAG, "bind $port failed: ${e.message}")
                lastEx = e
                if (e is BindException || e.cause is BindException ||
                    e.message?.contains("Permission denied", true) == true ||
                    e.message?.contains("EACCES", true) == true
                ) {
                    continue
                }
                // error อื่นก็ยังลอง fallback ก่อน throw
                continue
            }
        }
        lastError = lastEx?.message ?: "bind failed"
        throw lastEx ?: BindException("cannot bind $candidates")
    }

    @Synchronized
    fun stop() {
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop error: ${e.message}")
        } finally {
            started = false
            server = null
            runningPort = -1
        }
    }

    private fun buildServer(
        context: Context,
        prefs: Prefs,
        hostKey: File,
        port: Int,
    ): SshServer {
        val server = SshServer.setUpDefaultServer()
        server.port = port

        // Host key: auto-generate ครั้งแรก (RSA 2048)
        val keyProvider = SimpleGeneratorHostKeyProvider(Paths.get(hostKey.absolutePath))
        keyProvider.algorithm = "RSA"
        keyProvider.keySize = 2048
        server.keyPairProvider = keyProvider

        val username = prefs.username.ifBlank { "droid" }

        server.passwordAuthenticator = PasswordAuthenticator { u: String?, p: String?, _: ServerSession? ->
            if (u == null || p == null) return@PasswordAuthenticator false
            u == username && prefs.checkPassword(p)
        }

        server.publickeyAuthenticator = PublickeyAuthenticator { u: String?, key: PublicKey?, _: ServerSession? ->
            if (!prefs.keyAuthEnabled) return@PublickeyAuthenticator false
            if (u == null || key == null) return@PublickeyAuthenticator false
            if (u != username) return@PublickeyAuthenticator false
            isKeyAuthorized(context, prefs, key)
        }

        // Shell: root -> su, non-root -> sh
        val shellCmd: Array<String> = if (prefs.rootMode) {
            arrayOf("su", "-c", "sh -i")
        } else {
            // บางรอมมีแค่ /system/bin/sh บางรอมมี sh ใน PATH
            if (File("/system/bin/sh").canExecute()) arrayOf("/system/bin/sh", "-i")
            else arrayOf("sh", "-i")
        }
        // ProcessShellFactory(String command, List<String> args)
        val shellFactory = ProcessShellFactory(shellCmd[0], shellCmd.drop(1))
        server.shellFactory = shellFactory
        // one-shot `ssh user@host "cmd"` -> sh -c / su -c
        server.commandFactory = ExecCommandFactory(prefs.rootMode)

        server.subsystemFactories = listOf(SftpSubsystemFactory())

        return server
    }

    /** เทียบ public key ที่ login เข้ามากับไฟล์ authorized_keys (รองรับ options ข้างหน้า) */
    private fun isKeyAuthorized(context: Context, prefs: Prefs, key: PublicKey): Boolean {
        return try {
            val f = prefs.authorizedKeysFile(context)
            if (!f.exists()) return false
            val incoming = SshKeyUtils.encodeToOpenSsh(key) ?: return false
            val (inType, inBlob) = splitKeyLine(incoming) ?: return false
            f.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { splitKeyLine(it) }
                .any { (t, b) -> t == inType && b == inBlob }
        } catch (e: Exception) {
            Log.w(TAG, "key check failed: ${e.message}")
            false
        }
    }

    /** หา token ชนิดคีย์ (ssh-*/ecdsa-*) แล้วคืน (type, blob) */
    private fun splitKeyLine(line: String): Pair<String, String>? {
        val parts = line.trim().split(Regex("\\s+"))
        val i = parts.indexOfFirst { it.startsWith("ssh-") || it.startsWith("ecdsa-") }
        if (i < 0 || i + 1 >= parts.size) return null
        return parts[i] to parts[i + 1]
    }
}
