package com.tulipskun.droidssh

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** เก็บคอนฟิกแบบง่าย (private prefs, sideload only). */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("droid_ssh", Context.MODE_PRIVATE)

    var username: String
        get() = sp.getString(KEY_USER, "droid") ?: "droid"
        set(v) = sp.edit().putString(KEY_USER, v.ifBlank { "droid" }).apply()

    var password: String
        get() = sp.getString(KEY_PASS, "droid") ?: "droid"
        set(v) = sp.edit().putString(KEY_PASS, v.ifEmpty { "droid" }).apply()

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
        private const val KEY_PASS = "password"
        private const val KEY_ROOT = "root_mode"
        private const val KEY_AUTOSTART = "auto_start"
        private const val KEY_KEYAUTH = "key_auth"
    }
}
