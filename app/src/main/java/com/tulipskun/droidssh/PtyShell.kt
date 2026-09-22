package com.tulipskun.droidssh

import android.content.Context
import android.util.Log
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.Signal
import org.apache.sshd.server.SignalListener
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.server.shell.ShellFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Shell ผ่าน PTY จริง (forkpty ผ่าน JNI) -> ได้ tty เต็มรูปแบบ:
 * ไม่มี warning "can't find tty fd", job control ใช้ได้, vim/top/stty ทำงาน
 * ถ้า native lib ไม่มี -> fallback ไป piped shell แบบเดิม
 */
class PtyShellFactory(private val app: Context) : ShellFactory {

    override fun createShell(channel: ChannelSession): Command {
        val ctx = app.applicationContext
        val prefs = Prefs(ctx)
        var user = "droid"
        try {
            val s = channel.session
            if (s is ServerSession) user = s.username ?: "droid"
        } catch (_: Exception) {
        }
        if (!PtyNative.isAvailable()) {
            Log.w(TAG, "native pty missing, piped fallback")
            return pipedFallback(prefs.rootMode, channel)
        }
        val wantRoot = prefs.rootMode
        val useRoot = wantRoot && ShellEnv.suAvailable()
        if (wantRoot && !useRoot) {
            Log.w(TAG, "root mode ON แต่เรียก su ไม่ได้ (ยังไม่ grant ใน KernelSU?) — ใช้ app shell ไปก่อน")
        }
        return PtyCommand(ctx, user, useRoot)
    }

    private fun pipedFallback(rootMode: Boolean, channel: ChannelSession): Command {
        val cmd: Array<String> = if (rootMode) {
            arrayOf("su", "-c", "sh -i")
        } else {
            if (File("/system/bin/sh").canExecute()) arrayOf("/system/bin/sh", "-i")
            else arrayOf("sh", "-i")
        }
        // argv เต็ม (raw + elements) — บทเรียนรอบก่อน: elements คือของที่รันจริง
        return ProcessShellFactory(cmd.joinToString(" "), cmd.toList()).createShell(channel)
    }

    companion object {
        private const val TAG = "PtyShellFactory"
    }
}

private class PtyCommand(
    private val ctx: Context,
    private val username: String,
    private val rootMode: Boolean,
) : Command {
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var callback: ExitCallback? = null

    @Volatile private var masterFd: Int = -1
    @Volatile private var childPid: Int = -1
    @Volatile private var cols: Int = 80
    @Volatile private var rows: Int = 24

    override fun setInputStream(input: InputStream) { this.input = input }
    override fun setOutputStream(output: OutputStream) { this.output = output }
    override fun setErrorStream(error: OutputStream) { /* PTY รวม stderr ไว้ในสตรีมเดียวแล้ว */ }
    override fun setExitCallback(callback: ExitCallback) { this.callback = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        // pty-req มาถึงก่อน shell เสมอ -> ขนาด+TERM รออยู่ใน env แล้ว
        env.getEnv()[Environment.ENV_TERM]?.takeIf { it.isNotBlank() }?.let { term0 = it }
        env.getEnv()[Environment.ENV_COLUMNS]?.toIntOrNull()?.let { cols = it.coerceIn(20, 500) }
        env.getEnv()[Environment.ENV_LINES]?.toIntOrNull()?.let { rows = it.coerceIn(5, 200) }
        try {
            env.addSignalListener(
                SignalListener { ch, signal ->
                    if (signal == Signal.WINCH) {
                        try {
                            val m = (ch as? ChannelSession)?.environment?.getEnv() ?: env.getEnv()
                            m[Environment.ENV_COLUMNS]?.toIntOrNull()?.let { cols = it.coerceIn(20, 500) }
                            m[Environment.ENV_LINES]?.toIntOrNull()?.let { rows = it.coerceIn(5, 200) }
                            val fd = masterFd
                            if (fd >= 0) PtyNative.ptyResize(fd, rows, cols)
                        } catch (_: Exception) {
                        }
                    }
                },
                Signal.WINCH,
            )
        } catch (_: Exception) {
        }
        thread(name = "droid-ssh-pty", isDaemon = true) { run() }
    }

    @Volatile private var term0: String = "xterm-256color"

    private fun run() {
        var code = 1
        try {
            val suBin = "/system/bin/su"
            val shBin = if (File("/system/bin/sh").canExecute()) "/system/bin/sh" else "sh"
            val suOk = rootMode && try {
                File(suBin).canExecute()
            } catch (_: Exception) {
                false
            }
            // absolute path หมด (ไม่พึ่ง PATH) + log argv ไว้ไล่บั๊ก
            val argv = if (suOk) arrayOf(suBin) else arrayOf(shBin, "-i")
            Log.i(TAG, "pty fork argv=${argv.toList()} user=$username rootMode=$rootMode")
            val home = ShellEnv.homeDir(ctx)
            val envp = ShellEnv.build(ctx, username, term0, emptyMap())
            val pidOut = intArrayOf(-1)
            val fd = PtyNative.forkPty(argv, envp, home.absolutePath, rows, cols, pidOut)
            if (fd < 0) throw java.io.IOException("forkPty failed, errno=${-fd}")
            masterFd = fd
            childPid = pidOut[0]
            val tIn = thread(isDaemon = true) { pumpClientToPty() }
            val tOut = thread(isDaemon = true) { pumpPtyToClient() }
            code = PtyNative.ptyWait(childPid)
            tIn.join(2000)
            tOut.join(2000)
        } catch (t: Throwable) {
            Log.w(TAG, "pty shell: ${t.message}")
            try { output?.write("shell failed: ${t.message}\n".toByteArray()) } catch (_: Exception) {}
        } finally {
            cleanup()
            try { callback?.onExit(code, "", false) } catch (_: Exception) {}
        }
    }

    private fun pumpClientToPty() {
        val src = input ?: return
        val buf = ByteArray(8192)
        try {
            while (true) {
                val fd = masterFd
                if (fd < 0) break
                val n = src.read(buf)
                if (n < 0) break
                var off = 0
                while (off < n) {
                    val w = PtyNative.ptyWrite(fd, buf, off, n - off)
                    if (w <= 0) return
                    off += w
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun pumpPtyToClient() {
        val dst = output ?: return
        val buf = ByteArray(8192)
        try {
            while (true) {
                val fd = masterFd
                if (fd < 0) break
                val n = PtyNative.ptyRead(fd, buf, 0, buf.size)
                if (n <= 0) break // EOF / EIO (slave ปิด) = จบ session
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { dst.flush() } catch (_: Exception) {}
        }
    }

    override fun destroy(channel: ChannelSession) {
        try {
            val pid = childPid
            Log.i(TAG, "destroy pid=$pid")
            if (pid > 0) PtyNative.ptyKill(pid, 9)
        } catch (_: Exception) {
        }
        cleanup()
    }

    private fun cleanup() {
        val fd = masterFd
        masterFd = -1
        childPid = -1
        if (fd >= 0) {
            try { PtyNative.ptyClose(fd) } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "PtyCommand"
    }
}
