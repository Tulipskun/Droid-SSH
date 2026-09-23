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
 * (mount) เหมือนเดิมทุกอย่าง แค่เปลี่ยน rootfs จาก Termux เป็น Debian
 *
 * เข้าใช้งาน: พิมพ์ `dlogin` ใน SSH (root ใน guest, apt ใช้งานได้ปกติ)
 */
object GuestManager {
    private const val TAG = "GuestManager"
    const val GUEST_VERSION = "debian-1"
    private const val RELEASE_TAG = "debian-12-guest-v1"

    private val GUEST_ZIP = mapOf("arm64-v8a" to "debian-guest-12-aarch64.zip")

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
        else -> "ยังไม่ติดตั้ง (โหลด ~65MB ครั้งเดียว)"
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
            for (d in listOf("bin", "sbin", "usr/bin", "usr/sbin", "lib", "lib64")) {
                File(g, d).listFiles()?.forEach { chmod(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "exec perms: ${e.message}")
        }
    }

    /** mount binds ที่จำเป็น (ต้อง root) — เรียกทุกครั้งก่อนเข้า guest ก็ได้ (idempotent) */
    fun ensureMounts(ctx: Context): Boolean {
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
            // Debian ใช้ /etc มาตรฐาน (ไม่ใช่ prefix แบบ Termux)
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
            // ลบ wrapper เก่าของ termux guest ทิ้ง (กันสับสน)
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
