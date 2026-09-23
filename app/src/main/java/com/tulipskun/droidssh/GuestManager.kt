package com.tulipskun.droidssh

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Termux guest (apt/pkg จาก repo จริงของ Termux) รันแบบ chroot + bind mounts
 * ต้องการ root (mount) — เครื่องที่ไม่มี root จะติดตั้งไม่ได้
 *
 * สถาปัตย์: rootfs มาจาก release asset (termux-guest-<arch>.zip),
 * login ผ่าน ~/bin/tlogin (droproot ลดสิทธิ์เป็น app user เพื่อให้ apt ทำงาน),
 * ~/bin/tlogin-root สำหรับ root shell เต็ม
 */
object GuestManager {
    private const val TAG = "GuestManager"
    const val GUEST_VERSION = 1
    private const val RELEASE_TAG = "termux-guest-v1"
    private const val GUEST_TERM_PATH = "/data/data/com.termux/files/usr/bin/bash"

    private val DROPROOT_ASSET = mapOf("arm64-v8a" to "droproot-arm64-v8a")
    private val GUEST_ZIP = mapOf("arm64-v8a" to "termux-guest-aarch64.zip")

    fun guestDir(ctx: Context): File = File(ctx.applicationContext.filesDir, "guest")

    fun supportedAbi(): String? =
        Build.SUPPORTED_ABIS.firstOrNull { it in DROPROOT_ASSET && it in GUEST_ZIP }

    fun isInstalled(ctx: Context): Boolean =
        try {
            File(guestDir(ctx), ".guest-version").readText().trim() == GUEST_VERSION.toString()
        } catch (_: Exception) {
            false
        }

    fun statusText(ctx: Context): String = when {
        supportedAbi() == null -> "CPU นี้ยังไม่รองรับ Termux guest"
        isInstalled(ctx) -> "ติดตั้งแล้ว — พิมพ์ tlogin ใน SSH เพื่อเข้า guest (apt/pkg)"
        else -> "ยังไม่ติดตั้ง (โหลด ~100MB ครั้งเดียว)"
    }

    /** blocking — เรียกนอก main thread. progress(downloadedBytes, totalBytes; total=-1 ถ้าไม่รู้) */
    fun install(ctx: Context, progress: (Long, Long) -> Unit): Result<Unit> {
        val app = ctx.applicationContext
        val abi = supportedAbi() ?: return Result.failure(IllegalStateException("CPU นี้ยังไม่รองรับ"))
        return try {
            val url =
                "https://github.com/Tulipskun/Droid-SSH/releases/download/$RELEASE_TAG/${GUEST_ZIP[abi]}"
            val zip = File(app.filesDir, "guest.zip.tmp")
            download(url, zip, progress)
            unzip(zip, guestDir(app))
            zip.delete()
            // droproot: ลดสิทธิ์ root -> app user ใน guest
            val drop = File(guestDir(app), "droproot")
            app.assets.open(DROPROOT_ASSET[abi]!!).use { ins ->
                FileOutputStream(drop).use { ins.copyTo(it) }
            }
            chmod(drop)
            File(guestDir(app), ".guest-version").writeText(GUEST_VERSION.toString())
            writeResolvConf(app)
            writeLoginWrappers(app)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "install: ${e.message}")
            Result.failure(e)
        }
    }

    /** mount binds ที่จำเป็น (ต้อง root) — เรียกทุกครั้งก่อนเข้า guest ก็ได้ (idempotent) */
    fun ensureMounts(ctx: Context): Boolean {
        if (!ShellEnv.suAvailable()) return false
        return try {
            val g = guestDir(ctx.applicationContext).absolutePath
            val sh = buildString {
                appendLine("G='$g'")
                appendLine("ok=1")
                appendLine("m() { grep -q \" \$G/$2 \" /proc/mounts || mount $3 --bind \"$1\" \"$G/$2\" || { echo \"mount $2 failed\"; ok=0; }; }")
                appendLine("mkdir -p \$G/system \$G/vendor \$G/apex \$G/dev \$G/proc \$G/sys \$G/sdcard \$G/linkerconfig")
                appendLine("m /system system")
                appendLine("m /vendor vendor")
                appendLine("grep -q \" \$G/apex \" /proc/mounts || mount --rbind /apex \$G/apex || { echo 'mount apex failed'; ok=0; }")
                appendLine("m /dev dev")
                appendLine("grep -q \" \$G/proc \" /proc/mounts || mount -t proc proc \$G/proc || { echo 'mount proc failed'; ok=0; }")
                appendLine("grep -q \" \$G/sys \" /proc/mounts || mount -t sysfs sys \$G/sys || { echo 'mount sys failed'; ok=0; }")
                appendLine("m /sdcard sdcard || true")
                appendLine("m /linkerconfig linkerconfig || true")
                appendLine("[ -d \$G/proc/self ] && [ -x \$G/system/bin/sh ] && exit 0 || exit 1")
            }
            val f = File(ctx.applicationContext.filesDir, "guest-mount.sh")
            f.writeText(sh)
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
            val uid = Process.myUid()
            val groups = try {
                Os.getgroups().joinToString(",")
            } catch (_: Exception) {
                "$uid"
            }
            val bin = File(ShellEnv.homeDir(app), "bin").apply { mkdirs() }
            File(bin, "tlogin").writeText(
                "#!/system/bin/sh\n" +
                    "# เข้า Termux guest (มี apt/pkg) ในฐานะ user ปกติ\n" +
                    "GUEST='$g'\n" +
                    "[ -x \"\$GUEST/droproot\" ] || { echo 'ยังไม่ติดตั้ง guest'; exit 1; }\n" +
                    "exec su -c \"chroot \\\"$GUEST\\\" /droproot $uid $uid $groups -- $GUEST_TERM_PATH -l\"\n"
            )
            File(bin, "tlogin-root").writeText(
                "#!/system/bin/sh\n" +
                    "# เข้า Termux guest ในฐานะ root\n" +
                    "GUEST='$g'\n" +
                    "exec su -c \"chroot \\\"$GUEST\\\" $GUEST_TERM_PATH -l\"\n"
            )
            chmod(File(bin, "tlogin"))
            chmod(File(bin, "tlogin-root"))
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
        // ไฟล์รันได้ใน guest
        for (d in listOf("data/data/com.termux/files/usr/bin", "data/data/com.termux/files/usr/libexec")) {
            File(dest, d).listFiles()?.forEach { chmod(it) }
        }
    }

    private fun chmod(f: File) {
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", f.absolutePath)).waitFor()
        } catch (_: Exception) {
        }
    }
}
