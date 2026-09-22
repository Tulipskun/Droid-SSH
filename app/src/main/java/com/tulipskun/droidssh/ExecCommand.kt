package com.tulipskun.droidssh

import android.content.Context
import android.util.Log
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.session.ServerSession
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * รองรับ one-shot `ssh user@host "cmd"`:
 * non-root -> `sh -c <cmd>`, root mode -> `su -c <cmd>`
 * ได้ workspace env เดียวกับ shell (HOME/cwd ที่ home) แต่ไม่โหลด .droidrc
 */
class ExecCommandFactory(
    private val app: Context,
    private val rootMode: Boolean,
) : CommandFactory {
    private val shellBin: String =
        if (rootMode) "su"
        else if (java.io.File("/system/bin/sh").canExecute()) "/system/bin/sh"
        else "sh"

    override fun createCommand(channel: ChannelSession, command: String): Command {
        var user = "droid"
        try {
            val s = channel.session
            if (s is ServerSession) user = s.username ?: "droid"
        } catch (_: Exception) {
        }
        return ExecCommand(app.applicationContext, shellBin, command, user)
    }
}

private class ExecCommand(
    private val app: Context,
    private val shellBin: String,
    private val cmd: String,
    private val username: String,
) : Command {
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var error: OutputStream? = null
    private var callback: ExitCallback? = null

    @Volatile private var process: Process? = null

    override fun setInputStream(input: InputStream) { this.input = input }
    override fun setOutputStream(output: OutputStream) { this.output = output }
    override fun setErrorStream(error: OutputStream) { this.error = error }
    override fun setExitCallback(callback: ExitCallback) { this.callback = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        Thread({
            var code = 1
            try {
                // workspace เดียวกับ shell: cwd=home + HOME/USER/PATH (ไม่โหลด .droidrc เพราะไม่ใช่ interactive)
                val term = env.getEnv()[Environment.ENV_TERM].orEmpty()
                val home = ShellEnv.homeDir(app)
                val envMap = mutableMapOf<String, String>()
                for (kv in ShellEnv.build(app, username, term, emptyMap())) {
                    val i = kv.indexOf('=')
                    if (i > 0) envMap[kv.substring(0, i)] = kv.substring(i + 1)
                }
                envMap.remove("ENV")
                fun launch(bin: String): Process {
                    val pb = ProcessBuilder(bin, "-c", cmd)
                    pb.directory(home)
                    pb.environment().putAll(envMap)
                    return pb.start()
                }
                val p = try {
                    launch(shellBin)
                } catch (e: IOException) {
                    // fallback: shell อีกตัว (เช่น su ไม่มี -> ใช้ sh)
                    launch(if (shellBin == "su") "sh" else shellBin)
                }
                process = p
                val tIn = Thread { pump(input, p.outputStream, closeDst = true) }
                val tOut = Thread { pump(p.inputStream, output, closeDst = false) }
                val tErr = Thread { pump(p.errorStream, error, closeDst = false) }
                tIn.start(); tOut.start(); tErr.start()
                code = p.waitFor()
                tOut.join(5000); tErr.join(5000)
            } catch (e: Exception) {
                Log.w(TAG, "exec failed: ${e.message}")
                try { error?.write("exec failed: ${e.message}\n".toByteArray()) } catch (_: Exception) {}
            } finally {
                process = null
                try { callback?.onExit(code, "", false) } catch (_: Exception) {}
            }
        }, "droid-ssh-exec").start()
    }

    override fun destroy(channel: ChannelSession) {
        try { process?.destroy() } catch (_: Exception) {}
        process = null
    }

    private fun pump(src: InputStream?, dst: OutputStream?, closeDst: Boolean) {
        if (src == null || dst == null) return
        try {
            val buf = ByteArray(8192)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {
            // client ปิด / process ตาย -> หยุด pump
        } finally {
            if (closeDst) {
                try { dst.close() } catch (_: Exception) {}
            } else {
                try { dst.flush() } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val TAG = "ExecCommand"
    }
}
