package com.tulipskun.droidssh

import android.util.Log
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * รองรับ one-shot `ssh user@host "cmd"`:
 * non-root -> `sh -c <cmd>`, root mode -> `su -c <cmd>`
 */
class ExecCommandFactory(private val rootMode: Boolean) : CommandFactory {
    private val shellBin: String =
        if (rootMode) "su"
        else if (java.io.File("/system/bin/sh").canExecute()) "/system/bin/sh"
        else "sh"

    override fun createCommand(channel: ChannelSession, command: String): Command =
        ExecCommand(shellBin, command)
}

private class ExecCommand(
    private val shellBin: String,
    private val cmd: String,
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
                val p = try {
                    ProcessBuilder(shellBin, "-c", cmd).start()
                } catch (e: IOException) {
                    // fallback: shell อีกตัว (เช่น su ไม่มี -> ใช้ sh)
                    ProcessBuilder(if (shellBin == "su") "sh" else shellBin, "-c", cmd).start()
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
