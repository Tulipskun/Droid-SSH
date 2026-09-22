package com.tulipskun.droidssh

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.interfaces.RSAPublicKey

/** แปลง PublicKey เป็นบรรทัด OpenSSH (รองรับ RSA เต็ม, ชนิดอื่นคืน null). */
object SshKeyUtils {
    fun encodeToOpenSsh(key: java.security.PublicKey): String? {
        return when (key) {
            is RSAPublicKey -> encodeRsa(key)
            else -> null // EC/Ed25519: ใช้วิธี compare ผ่าน sshd parser แทน (ดู SshServerManager)
        }
    }

    private fun encodeRsa(key: RSAPublicKey): String {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        writeString(dos, "ssh-rsa".toByteArray())
        writeMpInt(dos, key.publicExponent.toByteArray())
        writeMpInt(dos, key.modulus.toByteArray())
        dos.flush()
        val blob = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return "ssh-rsa $blob"
    }

    private fun writeString(dos: DataOutputStream, data: ByteArray) {
        dos.writeInt(data.size)
        dos.write(data)
    }

    private fun writeMpInt(dos: DataOutputStream, data: ByteArray) {
        // ตัด leading zero แล้วเติม 0x00 ถ้า high-bit = 1 (mpint)
        var start = 0
        while (start < data.size - 1 && data[start] == 0.toByte()) start++
        var len = data.size - start
        val needsPad = (data[start].toInt() and 0x80) != 0
        if (needsPad) len += 1
        dos.writeInt(len)
        if (needsPad) dos.writeByte(0)
        dos.write(data, start, data.size - start)
    }
}
