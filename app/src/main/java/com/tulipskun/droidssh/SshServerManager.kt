package com.tulipskun.droidssh

import android.content.Context
import android.util.Log
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.subsystem.SubsystemFactory
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
    /** สถานะ sshd ใน Debian guest (null = ไม่ได้เปิด) */
    @Volatile var debianStatus: String? = null
        private set
    @Volatile private var appCtx: Context? = null

    val isRunning: Boolean get() = started && server != null

    @Synchronized
    fun start(context: Context): Int {
        if (isRunning) return runningPort
        lastError = null
        val app = context.applicationContext
        val prefs = Prefs(app)

        // FIX: MINA sshd อ่าน user.home ใน static init (ServerBuilder clinit)
        // บน Android property นี้ว่าง -> "No user home" (เป็น Error หลุด catch Exception)
        // ชี้ home ไปที่ sandbox ของแอปก่อนแตะคลาส sshd ใดๆ
        if (System.getProperty("user.home").isNullOrEmpty()) {
            System.setProperty("user.home", app.filesDir.absolutePath)
        }
        // FIX: auto-detect Android ของ sshd 2.12.0 เสีย (เช็คชื่อ property แทนค่า)
        // บังคับเองเพื่อให้เข้า branch เฉพาะ Android (เช่น เลี่ยง JMX ใน peelException)
        // หมายเหตุ: ยังมี javax.management stubs กันอีกชั้น เผื่อ path ที่ไม่เช็ค flag
        OsUtils.setAndroid(true)
        System.setProperty(OsUtils.CURRENT_USER_OVERRIDE_PROP, prefs.username.ifBlank { "droid" })

        // Root mode: เปิด NAT 22->2222 ผ่าน iptables (idempotent) เพื่อให้ ssh -p 22
        // ใช้งานได้จริง (แอป bind port <1024 ตรงๆ ไม่ได้) — ต้อง grant root ก่อน
        if (prefs.rootMode && ShellEnv.suAvailable()) {
            try {
                val nat = "iptables -t nat -C PREROUTING -p tcp --dport 22 " +
                    "-j REDIRECT --to-port ${Prefs.PORT_NONROOT} 2>/dev/null || " +
                    "iptables -t nat -A PREROUTING -p tcp --dport 22 " +
                    "-j REDIRECT --to-port ${Prefs.PORT_NONROOT}; " +
                    "iptables -t nat -C OUTPUT -o lo -p tcp --dport 22 " +
                    "-j REDIRECT --to-port ${Prefs.PORT_NONROOT} 2>/dev/null || " +
                    "iptables -t nat -A OUTPUT -o lo -p tcp --dport 22 " +
                    "-j REDIRECT --to-port ${Prefs.PORT_NONROOT}"
                val p = ProcessBuilder("su", "-c", nat).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                val rc = p.waitFor()
                Log.i(TAG, "iptables NAT 22->${Prefs.PORT_NONROOT} rc=$rc $out")
            } catch (e: Exception) {
                Log.w(TAG, "iptables: ${e.message}")
            }
        }

        // Guest (Debian): รีเฟรช wrapper + mount ใหม่ทุกครั้งที่สตาร์ท (mount หายหลังรีบูต)
        // + เปิด sshd ตรงจากใน Debian (:2223) ถ้าตั้งค่าไว้ — พังก็แค่ log ห้ามล้ม sshd หลัก
        if (GuestManager.isInstalled(app)) {
            try {
                GuestManager.ensureGuestDirs(app)
                GuestManager.ensureExecPerms(app)
                GuestManager.writeLoginWrappers(app)
                GuestManager.writeResolvConf(app)
                GuestManager.ensureMounts(app, ShellEnv.homeDir(app).absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "guest setup: ${e.message}")
            }
        }

        val sshDir = File(app.filesDir, "ssh").apply { mkdirs() }
        val hostKey = File(sshDir, "hostkey.ser")

        // ลำดับ port ที่จะลอง: ตามโหมดก่อน แล้ว fallback อีก port
        val wanted = prefs.effectivePort()
        val fallback = if (wanted == Prefs.PORT_ROOT) Prefs.PORT_NONROOT else Prefs.PORT_ROOT
        val candidates = listOf(wanted, fallback).distinct()

        var lastEx: Throwable? = null
        for (port in candidates) {
            try {
                val s = buildServer(app, prefs, hostKey, port)
                s.start()
                server = s
                started = true
                runningPort = port
                appCtx = app
                Log.i(TAG, "sshd started on port $port root=${prefs.rootMode}")
                // Debian sshd ตรง (:2223) — ทำหลัง bind หลักสำเร็จ, พังก็แค่ log
                if (GuestManager.isInstalled(app) && prefs.debianSshEnabled && ShellEnv.suAvailable()) {
                    try {
                        val keys = try {
                            prefs.authorizedKeysFile(app).takeIf { it.exists() }?.readText().orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                        debianStatus = GuestManager.ensureDebianSshd(
                            app, prefs.debianPassword(), keys,
                            ShellEnv.homeDir(app).absolutePath
                        )
                        Log.i(TAG, "debian sshd: $debianStatus")
                    } catch (e: Exception) {
                        debianStatus = "Debian SSH เปิดไม่สำเร็จ: ${e.message}"
                        Log.w(TAG, "debian sshd: ${e.message}")
                    }
                } else {
                    debianStatus = null
                }
                return port
            } catch (e: Throwable) {
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
        } catch (e: Throwable) {
            Log.w(TAG, "stop error: ${e.message}")
        } finally {
            started = false
            server = null
            runningPort = -1
        }
        try {
            appCtx?.let { GuestManager.stopDebianSshd(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "debian stop: ${e.message}")
        } finally {
            debianStatus = null
            appCtx = null
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

        // Shell ผ่าน PTY จริง (มี tty -> ไม่มี warning, job control/vim ใช้ได้)
        // workspace ส่วนตัว (HOME/env/.droidrc) อยู่ใน PtyShellFactory/ShellEnv
        server.shellFactory = PtyShellFactory(context.applicationContext)
        // one-shot `ssh user@host "cmd"` -> sh -c / su -c (workspace เดียวกับ shell)
        server.commandFactory = ExecCommandFactory(context.applicationContext, prefs.rootMode)

        // SFTP เริ่มที่ home (bare ls/put/get ใช้ได้) แต่ absolute path ยังเห็นทั้งเครื่อง
        val sftpBase = SftpSubsystemFactory()
        val sftpHome = ShellEnv.homeDir(context).toPath()
        val sftpFactory = object : SubsystemFactory {
            override fun getName(): String = SftpSubsystemFactory.NAME
            override fun createSubsystem(channel: ChannelSession): Command =
                HomeSftpSubsystem(channel, sftpBase, sftpHome)
        }
        server.subsystemFactories = listOf(sftpFactory)

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

    /** หา token ชนิดคีย์ (ssh-xxx / ecdsa-xxx) แล้วคืน (type, blob) */
    private fun splitKeyLine(line: String): Pair<String, String>? {
        val parts = line.trim().split(Regex("\\s+"))
        val i = parts.indexOfFirst { it.startsWith("ssh-") || it.startsWith("ecdsa-") }
        if (i < 0 || i + 1 >= parts.size) return null
        return parts[i] to parts[i + 1]
    }
}
