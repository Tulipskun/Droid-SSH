package com.tulipskun.droidssh

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey

/**
 * แปลง PublicKey เป็นบรรทัด OpenSSH สำหรับเทียบกับ authorized_keys
 * - RSA: ผ่าน RSAPublicKey โดยตรง
 * - ECDSA (P-256/384/521) + Ed25519: parse X.509 DER (SubjectPublicKeyInfo)
 *   แล้วประกอบ blob ตาม RFC 4253/5656/8709 ( covered by local prototype vs ssh-keygen )
 */
object SshKeyUtils {
    fun encodeToOpenSsh(key: PublicKey): String? {
        (key as? RSAPublicKey)?.let { return encodeRsa(it) }
        return encodeFromX509(key.encoded)
    }

    // ---------- RSA ----------

    private fun encodeRsa(key: RSAPublicKey): String {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        writeString(dos, "ssh-rsa".toByteArray())
        writeMpInt(dos, key.publicExponent.toByteArray())
        writeMpInt(dos, key.modulus.toByteArray())
        dos.flush()
        return "ssh-rsa " + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun writeString(dos: DataOutputStream, data: ByteArray) {
        dos.writeInt(data.size)
        dos.write(data)
    }

    private fun writeMpInt(dos: DataOutputStream, data: ByteArray) {
        var start = 0
        while (start < data.size - 1 && data[start] == 0.toByte()) start++
        var len = data.size - start
        if ((data[start].toInt() and 0x80) != 0) len += 1
        dos.writeInt(len)
        if (len != data.size - start) dos.writeByte(0)
        dos.write(data, start, data.size - start)
    }

    // ---------- X.509 DER (EC / Ed25519) ----------

    private const val OID_EC_PUBLIC_KEY = "2a8648ce3d0201"
    private const val OID_ED25519 = "2b6570"

    // named-curve OID -> (ssh key type, ssh curve name)
    private val CURVES = mapOf(
        "2a8648ce3d030107" to ("ecdsa-sha2-nistp256" to "nistp256"),
        "2b81040022" to ("ecdsa-sha2-nistp384" to "nistp384"),
        "2b81040023" to ("ecdsa-sha2-nistp521" to "nistp521"),
    )

    private fun encodeFromX509(der: ByteArray?): String? {
        if (der == null || der.size < 16) return null
        try {
            var pos = 0
            if (der[pos++] != 0x30.toByte()) return null // SPKI SEQUENCE
            pos = readLen(der, pos).second
            if (der[pos++] != 0x30.toByte()) return null // AlgorithmIdentifier
            pos = readLen(der, pos).second
            if (der[pos++] != 0x06.toByte()) return null // algorithm OID
            val oidLen = der[pos++].toInt() and 0xFF
            val algOid = der.copyOfRange(pos, pos + oidLen).toHex()
            pos += oidLen
            var paramsOid: String? = null
            if (der[pos] == 0x06.toByte()) { // named-curve OID (absent for Ed25519)
                val plen = der[pos + 1].toInt() and 0xFF
                paramsOid = der.copyOfRange(pos + 2, pos + 2 + plen).toHex()
                pos += 2 + plen
            }
            if (der[pos++] != 0x03.toByte()) return null // BIT STRING
            val (blen, bp0) = readLen(der, pos)
            var bp = bp0
            if (der[bp++] != 0x00.toByte()) return null // unused bits must be 0
            val raw = der.copyOfRange(bp, bp + blen - 1)

            val out = ByteArrayOutputStream()
            val dos = DataOutputStream(out)
            val sshType: String = when (algOid) {
                OID_EC_PUBLIC_KEY -> {
                    val curve = CURVES[paramsOid] ?: return null
                    writeString(dos, curve.first.toByteArray())
                    writeString(dos, curve.second.toByteArray())
                    writeString(dos, raw)
                    curve.first
                }
                OID_ED25519 -> {
                    if (raw.size != 32) return null
                    writeString(dos, "ssh-ed25519".toByteArray())
                    writeString(dos, raw)
                    "ssh-ed25519"
                }
                else -> return null
            }
            dos.flush()
            return sshType + " " + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            return null
        }
    }

    private fun readLen(d: ByteArray, pos0: Int): Pair<Int, Int> {
        var pos = pos0
        val b = d[pos++].toInt() and 0xFF
        if (b and 0x80 == 0) return b to pos
        val n = b and 0x7F
        var len = 0
        repeat(n) { len = (len shl 8) or (d[pos++].toInt() and 0xFF) }
        return len to pos
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(v.toString(16))
        }
        return sb.toString()
    }
}
