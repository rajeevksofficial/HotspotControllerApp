package com.hotspot.controller.network

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotNetworkHelper {

    /**
     * Finds the local IP address of D0 on the hotspot/tether interface or Wi-Fi interface.
     */
    fun getHotspotOrLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var fallbackIp = "192.168.43.1"

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name.lowercase()

                // Check for typical hotspot interface names: ap0, wlan1, wlan0, rndis0, swlan0, softap0
                val isHotspotInterface = name.startsWith("ap") ||
                        name.startsWith("softap") ||
                        name.startsWith("wlan1") ||
                        name.startsWith("rndis") ||
                        name.contains("tether")

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        if (isHotspotInterface) {
                            return hostAddress
                        }
                        if (hostAddress.startsWith("192.168.43.") || hostAddress.startsWith("192.168.49.") || hostAddress.startsWith("192.168.50.")) {
                            return hostAddress
                        }
                        fallbackIp = hostAddress
                    }
                }
            }
            return fallbackIp
        } catch (_: Exception) {
            return "192.168.43.1"
        }
    }

    /**
     * Checks if Wi-Fi AP is active using reflection on WifiManager.
     */
    fun isWifiApEnabled(context: Context): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates an intent to navigate directly to Android's Hotspot/Tethering settings.
     */
    fun getTetheringSettingsIntent(): Intent {
        val intent = Intent()
        intent.setClassName("com.android.settings", "com.android.settings.TetherSettings")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return intent
    }

    /**
     * Fallback intent to wireless settings.
     */
    fun getWirelessSettingsIntent(): Intent {
        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return intent
    }

    /**
     * Returns the name of the hotspot or tethering network interface (e.g. ap0, wlan1, etc.)
     */
    fun getHotspotInterfaceName(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name.lowercase()
                if (name.startsWith("ap") || name.startsWith("softap") || name.startsWith("wlan1") || name.startsWith("rndis") || name.startsWith("swlan")) {
                    return iface.name
                }
            }
        } catch (_: Exception) {}
        return "ap0"
    }
}
