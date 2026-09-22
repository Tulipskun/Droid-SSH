package com.tulipskun.droidssh

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** เก็บคอนฟิก (private prefs, sideload only). รหัสผ่านเก็บเป็น SHA-256+salt ไม่เก็บ plaintext. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("droid_ssh", Context.MODE_PRIVATE)

    var username: String
        get() = sp.getString(KEY_USER, "droid") ?: "droid"
        set(v) = sp.edit().putString(KEY_USER, v.ifBlank { "droid" }).apply()

    /** ตั้งรหัสผ่านใหม่ (hash + เก็บ). */
    fun setPassword(raw: String) {
        val pw = raw.ifEmpty { "droid" }
        var salt = sp.getString(KEY_SALT, null)
        if (salt.isNullOrEmpty()) {
            salt = genSalt()
            sp.edit().putString(KEY_SALT, salt).apply()
        }
        sp.edit()
            .putString(KEY_PASSHASH, sha256Hex("$salt:$pw"))
            .remove(KEY_PASS) // ลบร่องรอย plaintext รุ่นเก่า
            .apply()
    }

    /** ตรวจรหัสผ่านตอน login (รองรับ migrate จาก plaintext รุ่นเก่า). */
    fun checkPassword(raw: String): Boolean {
        val salt = sp.getString(KEY_SALT, null)
        val hash = sp.getString(KEY_PASSHASH, null)
        if (!salt.isNullOrEmpty() && !hash.isNullOrEmpty()) {
            return sha256Hex("$salt:$raw") == hash
        }
        // migrate: ของเดิมเก็บ plaintext -> ตรงแล้ว hash ทันที
        val legacy = sp.getString(KEY_PASS, null)
        if (legacy != null) {
            if (legacy == raw) {
                setPassword(raw)
                return true
            }
            return false
        }
        // ติดตั้งใหม่ยังไม่เคยตั้ง -> default "droid"
        return raw == "droid"
    }

    fun hasCustomPassword(): Boolean =
        sp.contains(KEY_PASSHASH) || sp.contains(KEY_PASS)

    /** root mode = พยายามใช้ port 22 + shell ผ่าน su */
    var rootMode: Boolean
        get() = sp.getBoolean(KEY_ROOT, false)
        set(v) = sp.edit().putBoolean(KEY_ROOT, v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, true)
        set(v) = sp.edit().putBoolean(KEY_AUTOSTART, v).apply()

    var keyAuthEnabled: Boolean
        get() = sp.getBoolean(KEY_KEYAUTH, true)
        set(v) = sp.edit().putBoolean(KEY_KEYAUTH, v).apply()

    /** Port ที่ใช้จริง: root -> 22, non-root -> 2222 (ตามสเปก) */
    fun effectivePort(): Int = if (rootMode) PORT_ROOT else PORT_NONROOT

    fun authorizedKeysFile(context: Context): File =
        File(context.applicationContext.filesDir, "ssh/authorized_keys")

    companion object {
        const val PORT_ROOT = 22
        const val PORT_NONROOT = 2222

        private const val KEY_USER = "username"
        private const val KEY_PASS = "password" // legacy plaintext (migrate อัตโนมัติ)
        private const val KEY_PASSHASH = "password_sha256"
        private const val KEY_SALT = "password_salt"
        private const val KEY_ROOT = "root_mode"
        private const val KEY_AUTOSTART = "auto_start"
        private const val KEY_KEYAUTH = "key_auth"

        private fun genSalt(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return Base64.encodeToString(b, Base64.NO_WRAP)
        }

        private fun sha256Hex(s: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(d.size * 2)
            for (b in d) {
                val v = b.toInt() and 0xFF
                if (v < 16) sb.append('0')
                sb.append(v.toString(16))
            }
            return sb.toString()
        }
    }
}
