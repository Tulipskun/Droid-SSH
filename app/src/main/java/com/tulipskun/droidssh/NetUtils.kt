package com.tulipskun.droidssh

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/** หา IPv4 ของเครื่อง (ไม่มี permission พิเศษ, ใช้แสดงคำสั่งเชื่อมต่อให้ copy ได้เลย) */
object NetUtils {
    fun getDeviceIps(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        // 1) Wi-Fi IP มาก่อน (เครือข่ายหลักที่ ssh เข้า)
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val s = "%d.%d.%d.%d".format(ip and 0xFF, ip shr 8 and 0xFF, ip shr 16 and 0xFF, ip shr 24 and 0xFF)
                if (!s.startsWith("0.")) out.add(s)
            }
        } catch (e: Exception) {
            Log.w("NetUtils", "wifi ip: ${e.message}")
        }
        // 2) ทุก interface ที่ up (รวม hotspot/usb-tether)
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress() }
                .forEach { out.add(it.hostAddress ?: "") }
            out.remove("")
        } catch (e: Exception) {
            Log.w("NetUtils", "iface enum: ${e.message}")
        }
        return out.toList()
    }
}
