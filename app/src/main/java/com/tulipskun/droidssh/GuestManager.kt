package com.tulipskun.droidssh

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/**
 * Debian guest (apt จาก Debian ตรงๆ) รันแบบ chroot + bind mounts — ต้องใช้ root
 * (mount) เหมือนเดิมทุกอย่าง
 *
 * เข้าใช้งาน: พิมพ์ `dlogin` ใน SSH (root ใน guest, apt ใช้งานได้ปกติ)
 */
object GuestManager {
    private const val TAG = "GuestManager"
    const val GUEST_VERSION = "debian13-1"
    private const val RELEASE_TAG = "debian-13-guest-v1"

    private val GUEST_ZIP = mapOf("arm64-v8a" to "debian-guest-13-aarch64.zip")

    fun guestDir(ctx: Context): File = File(ctx.applicationContext.filesDir, "guest")

    fun supportedAbi(): String? =
        Build.SUPPORTED_ABIS.firstOrNull { it in GUEST_ZIP }

    fun isInstalled(ctx: Context): Boolean =
        try {
            File(guestDir(ctx), ".guest-version").readText().trim() == GUEST_VERSION
        } catch (_: Exception) {
            false
        }

    fun statusText(ctx: Context): String = when {
        supportedAbi() == null -> "CPU นี้ยังไม่รองรับ Debian guest"
        isInstalled(ctx) -> "ติดตั้งแล้ว — พิมพ์ dlogin ใน SSH เพื่อเข้า Debian (apt)"
        else -> "ยังไม่ติดตั้ง (โหลด ~50MB ครั้งเดียว)"
    }

    /** blocking — เรียกนอก main thread. progress(downloadedBytes, totalBytes; total=-1 ถ้าไม่รู้) */
    fun install(ctx: Context, progress: (Long, Long) -> Unit): Result<Unit> {
        val app = ctx.applicationContext
        val abi = supportedAbi() ?: return Result.failure(IllegalStateException("CPU นี้ยังไม่รองรับ"))
        return try {
            // อัปเกรด/เปลี่ยน guest: ล้างของเก่าทิ้งก่อน
            if (guestDir(app).exists()) guestDir(app).deleteRecursively()
            guestDir(app).mkdirs()
            val url =
                "https://github.com/Tulipskun/Droid-SSH/releases/download/$RELEASE_TAG/${GUEST_ZIP[abi]}"
            val zip = File(app.filesDir, "guest.zip.tmp")
            download(url, zip, progress)
            unzip(zip, guestDir(app))
            zip.delete()
            File(guestDir(app), ".guest-version").writeText(GUEST_VERSION)
            ensureGuestDirs(app)
            ensureExecPerms(app)
            writeResolvConf(app)
            writeLoginWrappers(app)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "install: ${e.message}")
            Result.failure(e)
        }
    }

    /** โฟลเดอร์ที่ apt/dpkg ต้องใช้ + home ของ root */
    fun ensureGuestDirs(ctx: Context) {
        try {
            val g = guestDir(ctx.applicationContext)
            if (!File(g, ".guest-version").exists()) return
            val dirs = listOf(
                "var/lib/apt/lists/partial",
                "var/cache/apt/archives/partial",
                "var/lib/dpkg/updates",
                "var/lib/dpkg/info",
                "var/log/apt",
                "etc/apt/apt.conf.d",
                "etc/apt/sources.list.d",
                "etc/apt/preferences.d",
                "root",
                "tmp",
                "home/user",
            )
            for (d in dirs) File(g, d).mkdirs()
            val status = File(g, "var/lib/dpkg/status")
            if (!status.exists()) status.writeText("")
        } catch (e: Exception) {
            Log.w(TAG, "guest dirs: ${e.message}")
        }
    }

    /** ไบนารีใน guest ต้อง +x (unzip ไม่เก็บ permission) */
    fun ensureExecPerms(ctx: Context) {
        try {
            val g = guestDir(ctx.applicationContext)
            // Debian merged-usr: ของจริงอยู่ usr/bin, usr/sbin; bin/sbin/lib* เป็น symlink
            for (d in listOf("bin", "sbin", "usr/bin", "usr/sbin", "lib", "lib64", "usr/lib/apt/methods")) {
                File(g, d).listFiles()?.forEach { if (it.isFile) chmod(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "exec perms: ${e.message}")
        }
    }

    /** mount binds ที่จำเป็น (ต้อง root) — เรียกทุกครั้งก่อนเข้า guest ก็ได้ (idempotent) */
    fun ensureMounts(ctx: Context, androidHome: String? = null): Boolean {
        if (!ShellEnv.suAvailable()) return false
        return try {
            val g = guestDir(ctx.applicationContext).absolutePath
            val sb = StringBuilder()
            fun m(src: String, dst: String, opts: String = "--bind") {
                sb.appendLine("grep -q \" $g/$dst \" /proc/mounts || mount $opts \"$src\" \"$g/$dst\" || { echo \"mount $dst failed\"; ok=0; }")
            }
            sb.appendLine("ok=1")
            sb.appendLine("mkdir -p $g/system $g/vendor $g/apex $g/dev/pts $g/proc $g/sys $g/sdcard $g/linkerconfig")
            m("/system", "system")
            m("/vendor", "vendor")
            sb.appendLine("grep -q \" $g/apex \" /proc/mounts || mount --rbind /apex \"$g/apex\" || { echo 'mount apex failed'; ok=0; }")
            m("/dev", "dev")
            sb.appendLine("grep -q \" $g/dev/pts \" /proc/mounts || mount -t devpts devpts \"$g/dev/pts\" || { echo 'mount devpts failed'; ok=0; }")
            sb.appendLine("grep -q \" $g/proc \" /proc/mounts || mount -t proc proc \"$g/proc\" || { echo 'mount proc failed'; ok=0; }")
            sb.appendLine("grep -q \" $g/sys \" /proc/mounts || mount -t sysfs sys \"$g/sys\" || { echo 'mount sys failed'; ok=0; }")
            m("/sdcard", "sdcard")
            m("/linkerconfig", "linkerconfig")
            // ไฟล์ฝั่ง Android เข้าได้จากใน Debian ผ่าน /mnt/droid-home
            // (home ของแอป: มี bin/dlogin, storage -> /sdcard, ssh/authorized_keys)
            if (!androidHome.isNullOrBlank()) {
                sb.appendLine("mkdir -p $g/mnt/droid-home")
                sb.appendLine("grep -q \" $g/mnt/droid-home \" /proc/mounts || mount --bind \"$androidHome\" \"$g/mnt/droid-home\" || { echo 'mount droid-home failed'; ok=0; }")
            }
            sb.appendLine("[ -d $g/proc/self ] && [ -x $g/system/bin/sh ] && exit 0 || exit 1")
            val f = File(ctx.applicationContext.filesDir, "guest-mount.sh")
            f.writeText(sb.toString())
            val p = ProcessBuilder("su", "-c", "sh ${f.absolutePath}")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val exited = p.waitFor()
            Log.i(TAG, "ensureMounts rc=$exited $out")
            exited == 0
        } catch (e: Exception) {
            Log.w(TAG, "ensureMounts: ${e.message}")
            false
        }
    }

    // ---------- sshd ตรงจากใน Debian (openssh-server ใน chroot, port 2223) ----------

    const val DEBIAN_SSH_PORT = 2223
    private const val DEBIAN_SSH_PID = "/run/sshd_droid.pid"
    private const val DEBIAN_SSH_CONF = "/etc/ssh/sshd_config_droid"

    /** sshd ใน guest รันอยู่ไหม (เช็ค pidfile + kill -0) */
    fun debianSshdRunning(ctx: Context): Boolean {
        if (!isInstalled(ctx) || !ShellEnv.suAvailable()) return false
        val g = guestDir(ctx.applicationContext).absolutePath
        return try {
            val (rc, _) = suSh(ctx.applicationContext.filesDir,
                "PID=$(cat \"$g$DEBIAN_SSH_PID\" 2>/dev/null); " +
                    "[ -n \"$D{PID}\" ] && kill -0 \"$D{PID}\" 2>/dev/null"
            )
            rc == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * blocking (รันนอก main thread) — เตรียม + สตาร์ท sshd ใน Debian.
     * - ครั้งแรก: apt-get install openssh-server เอง (ต้องมีเน็ต)
     * - gen host keys, เขียน config, ตั้งรหัส root, sync authorized_keys, สตาร์ท daemon
     * คืนข้อความสถานะสั้นๆ (throw ถ้าพัง — caller จับเอง ห้ามล้มทั้ง sshd หลัก)
     */
    fun ensureDebianSshd(
        ctx: Context,
        rootPassword: String,
        authorizedKeys: String,
        androidHome: String? = null,
    ): String {
        val app = ctx.applicationContext
        if (!isInstalled(app)) throw IllegalStateException("ยังไม่ติดตั้ง Debian guest")
        if (!ShellEnv.suAvailable()) throw IllegalStateException("ต้องใช้ root")
        if (!ensureMounts(app, androidHome)) throw IllegalStateException("mount guest ไม่ครบ")
        val g = guestDir(app).absolutePath
        val d = D

        // 1) openssh-server (ข้ามถ้ามีแล้ว)
        val (_, out) = suSh(app.applicationContext.filesDir, "[ -x \"$g/usr/sbin/sshd\" ] && echo HAVE || echo MISSING")
        if (out.trim() == "MISSING") {
            val (irc, iout) = suSh(app.applicationContext.filesDir,
                "chroot \"$g\" /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                    "DEBIAN_FRONTEND=noninteractive apt-get update -o Acquire::AllowInsecureRepositories=false 2>&1 | tail -n 2; " +
                    "chroot \"$g\" /usr/bin/env PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server 2>&1 | tail -n 3; " +
                    "[ -x \"$g/usr/sbin/sshd\" ]"
            )
            if (irc != 0) throw IllegalStateException("ติดตั้ง openssh-server ไม่สำเร็จ: ${iout.trim().take(200)}")
        }

        // 2) host keys + user sshd (privsep) + โฟลเดอร์รันไทม์
        val (krc, kout) = suSh(app.applicationContext.filesDir,
            "ls \"$g\"/etc/ssh/ssh_host_*_key >/dev/null 2>&1 || " +
                "chroot \"$g\" /usr/bin/env PATH=/usr/sbin:/usr/bin:/sbin:/bin ssh-keygen -A >/dev/null 2>&1; " +
                "grep -q ^sshd: \"$g/etc/passwd\" || " +
                "echo 'sshd:x:74:74:Privilege-separated SSH:/run/sshd:/usr/sbin/nologin' >> \"$g/etc/passwd\"; " +
                "mkdir -p \"$g/run/sshd\" \"$g/root/.ssh\"; " +
                "chmod 700 \"$g/root/.ssh\"; " +
                "ls \"$g\"/etc/ssh/ssh_host_rsa_key >/dev/null 2>&1"
        )
        if (krc != 0) throw IllegalStateException("เตรียม host keys ไม่สำเร็จ: ${kout.trim().take(200)}")

        // 3) config ของ Droid-SSH (แยกไฟล์ ไม่แตะของ stock; ไม่ใช้ PAM — ใน chroot ไม่มี systemd)
        suSh(app.applicationContext.filesDir,
            "{ echo 'Port $DEBIAN_SSH_PORT'; echo 'ListenAddress 0.0.0.0'; " +
                "echo 'PermitRootLogin yes'; echo 'PasswordAuthentication yes'; " +
                "echo 'ChallengeResponseAuthentication no'; echo 'UsePAM no'; " +
                "echo 'X11Forwarding no'; echo 'PrintMotd no'; " +
                "echo 'PidFile $DEBIAN_SSH_PID'; " +
                "echo 'AuthorizedKeysFile .ssh/authorized_keys'; " +
                "echo 'Subsystem sftp /usr/lib/openssh/sftp-server'; } " +
                "> \"$g$DEBIAN_SSH_CONF\"; " +
                "chroot \"$g\" /usr/sbin/sshd -t -f $DEBIAN_SSH_CONF"
        ).also { (crc, cout) ->
            if (crc != 0) throw IllegalStateException("config sshd ไม่ผ่าน: ${cout.trim().take(200)}")
        }

        // 4) รหัส root (alphanumeric ล้วนจาก Prefs — ปลอดภัยต่อ quoting)
        val (prc, pout) = suSh(app.applicationContext.filesDir, "echo 'root:$rootPassword' | chroot \"$g\" /usr/sbin/chpasswd")
        if (prc != 0) throw IllegalStateException("ตั้งรหัส root ไม่สำเร็จ: ${pout.trim().take(200)}")

        // 5) authorized_keys (เขียนผ่านไฟล์ชั่วคราวเลี่ยง quoting; ต้องเป็นของ root:600 ไม่งั้น sshd ปฏิเสธ)
        val keys = authorizedKeys.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (keys.isNotEmpty()) {
            val tmp = File(app.filesDir, "debian-auth.tmp")
            tmp.writeText(keys.joinToString("\n", postfix = "\n"))
            suSh(app.applicationContext.filesDir,
                "cp '${tmp.absolutePath}' \"$g/root/.ssh/authorized_keys\"; " +
                    "chmod 600 \"$g/root/.ssh/authorized_keys\""
            )
            tmp.delete()
        } else {
            suSh(app.applicationContext.filesDir, "rm -f \"$g/root/.ssh/authorized_keys\"")
        }

        // 6) (รี)สตาร์ท daemon
        val (src, sout) = suSh(app.applicationContext.filesDir,
            "if [ -f \"$g$DEBIAN_SSH_PID\" ]; then kill \"$(cat \"$g$DEBIAN_SSH_PID\")\" 2>/dev/null; sleep 1; fi; " +
                "chroot \"$g\" /usr/sbin/sshd -f $DEBIAN_SSH_CONF -E /tmp/sshd_droid.log; sleep 1; " +
                "PID=$(cat \"$g$DEBIAN_SSH_PID\" 2>/dev/null); [ -n \"${d}PID\" ] && kill -0 \"${d}PID\""
        )
        if (src != 0) {
            val log = suSh(app.applicationContext.filesDir, "tail -n 5 \"$g/tmp/sshd_droid.log\" 2>/dev/null").second
            throw IllegalStateException("สตาร์ท sshd ไม่สำเร็จ: ${sout.trim().take(120)} ${log.trim().take(200)}")
        }
        // symlink สะดวกใน guest: ~/sdcard ~/android
        try {
            val r = File(guestDir(app), "root")
            symlinkForce(File(r, "sdcard"), "/sdcard")
            symlinkForce(File(r, "android"), "/mnt/droid-home")
        } catch (_: Exception) {
        }
        return "Debian SSH ตรงพร้อมใช้ :$DEBIAN_SSH_PORT"
    }

    /** หยุด sshd ใน guest (best-effort) */
    fun stopDebianSshd(ctx: Context) {
        try {
            if (!isInstalled(ctx)) return
            val g = guestDir(ctx.applicationContext).absolutePath
            suSh(ctx.applicationContext.filesDir, "if [ -f \"$g$DEBIAN_SSH_PID\" ]; then kill \"$(cat \"$g$DEBIAN_SSH_PID\")\" 2>/dev/null; rm -f \"$g$DEBIAN_SSH_PID\"; fi")
        } catch (_: Exception) {
        }
    }

    /** รัน shell script 1 ชุดผ่าน su — คืน (exit code, output รวม) */
    private fun suSh(tmpDir: File, script: String): Pair<Int, String> {
        val f = File.createTempFile("droid-su", ".sh", tmpDir).apply { writeText(script) }
        return try {
            val p = ProcessBuilder("su", "-c", "sh ${f.absolutePath}")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor() to out
        } finally {
            f.delete()
        }
    }

    private fun symlinkForce(link: File, target: String) {
        try {
            Files.deleteIfExists(link.toPath())
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), Paths.get(target))
        } catch (_: Exception) {
        }
    }

    fun writeResolvConf(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val dns = try {
                cm.getLinkProperties(cm.activeNetwork)
                    ?.dnsServers?.mapNotNull { it.hostAddress }?.take(3)
                    .orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            val servers = if (dns.isEmpty()) listOf("8.8.8.8", "1.1.1.1") else dns
            // Debian ใช้ /etc มาตรฐานของตัวเอง
            val etc = File(guestDir(app), "etc").apply { mkdirs() }
            File(etc, "resolv.conf").writeText(servers.joinToString("\n", postfix = "\n") { "nameserver $it" })
        } catch (e: Exception) {
            Log.w(TAG, "resolv: ${e.message}")
        }
    }

    fun writeLoginWrappers(ctx: Context) {
        try {
            val app = ctx.applicationContext
            val g = guestDir(app).absolutePath
            val bin = File(ShellEnv.homeDir(app), "bin").apply { mkdirs() }
            // ลบ wrapper เก่าทิ้ง (กันสับสนกับเวอร์ชันก่อน)
            File(bin, "tlogin").delete()
            File(bin, "tlogin-root").delete()
            val d = D
            File(bin, "dlogin").writeText(
                "#!/system/bin/sh\n" +
                    "# เข้า Debian guest (มี apt) ในฐานะ root\n" +
                    "GUEST='" + g + "'\n" +
                    "exec su -c \"chroot \\\"" + d + "GUEST\\\" " +
                    "/usr/bin/env HOME=/root " +
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                    "TERM=" + d + "{TERM:-xterm-256color} /bin/bash -l\"\n"
            )
            chmod(File(bin, "dlogin"))
        } catch (e: Exception) {
            Log.w(TAG, "wrappers: ${e.message}")
        }
    }

    private fun download(url: String, dest: File, progress: (Long, Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30000
            readTimeout = 60000
            setRequestProperty("User-Agent", "DroidSSH-guest-installer")
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("download http ${conn.responseCode}")
        }
        val total = conn.contentLengthLong
        var done = 0L
        BufferedInputStream(conn.inputStream).use { ins ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }
    }

    private fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                // กัน zip-slip
                val out = File(dest, e.name).canonicalFile
                if (!out.path.startsWith(dest.canonicalPath + File.separator)) {
                    throw SecurityException("bad zip entry: ${e.name}")
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it) }
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        // symlink manifest (.droid-links.txt): "t<TAB>path<TAB>target"
        val man = File(dest, ".droid-links.txt")
        if (man.exists()) {
            man.readLines().forEach { line ->
                val parts = line.split("\t")
                if (parts.size != 3) return@forEach
                try {
                    val link = File(dest, parts[1])
                    Files.deleteIfExists(link.toPath())
                    link.parentFile?.mkdirs()
                    Files.createSymbolicLink(link.toPath(), Paths.get(parts[2]))
                } catch (_: Exception) {
                }
            }
            man.delete()
        }
    }

    private fun chmod(f: File) {
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", f.absolutePath)).waitFor()
        } catch (_: Exception) {
        }
    }

    // $ สำหรับเขียน shell script (Kotlin string template ต้อง escape)
    private const val D = "$"
}
