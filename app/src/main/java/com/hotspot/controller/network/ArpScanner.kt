package com.hotspot.controller.network

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader

data class ArpEntry(
    val ip: String,
    val mac: String,
    val interfaceName: String,
    val deviceName: String? = null
)

object ArpScanner {
    private const val TAG = "ArpScanner"

    /**
     * Discovers connected network devices using multiple strategies:
     * 1. Direct unprivileged `ip neigh` system command
     * 2. Direct read of /proc/net/arp (on devices where permitted)
     * 3. Root execution `su` (if available) reading kernel neighbor table & dnsmasq DHCP leases
     */
    fun scanArpTable(): List<ArpEntry> {
        val resultMap = mutableMapOf<String, ArpEntry>()

        // Strategy 1: unprivileged `ip neigh` command
        try {
            val process = Runtime.getRuntime().exec("ip neigh")
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val entry = parseIpNeighLine(line)
                    if (entry != null) {
                        resultMap[entry.ip] = entry
                    }
                }
            }
        } catch (_: Exception) {}

        // Strategy 2: unprivileged `/proc/net/arp`
        try {
            val arpFile = File("/proc/net/arp")
            if (arpFile.exists() && arpFile.canRead()) {
                BufferedReader(FileReader(arpFile)).use { reader ->
                    var line: String? = reader.readLine() // Header
                    while (reader.readLine().also { line = it } != null) {
                        val tokens = line?.trim()?.split(Regex("\\s+")) ?: continue
                        if (tokens.size >= 6) {
                            val ip = tokens[0]
                            val mac = tokens[3]
                            val iface = tokens[5]
                            if (isValidMac(mac) && ip.contains(".")) {
                                resultMap[ip] = ArpEntry(ip, mac, iface)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Strategy 3: Superuser commands if device is rooted
        if (RootFirewallHelper.isDeviceRooted()) {
            try {
                // Query root ip neigh, modern & legacy DHCP leases, and arp table
                val suCommands = "ip neigh show; /system/bin/ip neigh show; cat /data/misc/apexdata/com.android.tethering/misc/dhcp/dnsmasq.leases 2>/dev/null; cat /data/misc/dhcp/dnsmasq.leases 2>/dev/null; cat /data/misc/wifi/dnsmasq.leases 2>/dev/null; cat /proc/net/arp 2>/dev/null"
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", suCommands))
                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line?.trim() ?: continue
                        // Check if dnsmasq lease format: timestamp MAC IP hostname client_id
                        val tokens = text.split(Regex("\\s+"))
                        if (tokens.size >= 4 && tokens[1].contains(":") && tokens[2].contains(".")) {
                            val mac = tokens[1]
                            val ip = tokens[2]
                            val name = if (tokens.size >= 4 && tokens[3] != "*") tokens[3] else null
                            if (isValidMac(mac)) {
                                resultMap[ip] = ArpEntry(ip = ip, mac = mac, interfaceName = "ap0", deviceName = name)
                            }
                        } else {
                            val neigh = parseIpNeighLine(text)
                            if (neigh != null) {
                                resultMap[neigh.ip] = neigh
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "su command error: ${e.message}")
            }
        }

        return resultMap.values.toList()
    }

    private fun parseIpNeighLine(line: String?): ArpEntry? {
        if (line.isNullOrBlank()) return null
        // Format example: "192.168.43.50 dev wlan1 lladdr 42:85:ec:56:87:c2 REACHABLE"
        // or "192.168.43.50 dev ap0 lladdr 42:85:ec:56:87:c2 STALE"
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.size >= 4) {
            val ip = tokens[0]
            val devIndex = tokens.indexOf("dev")
            val lladdrIndex = tokens.indexOf("lladdr")

            val iface = if (devIndex != -1 && devIndex + 1 < tokens.size) tokens[devIndex + 1] else "ap0"
            val mac = if (lladdrIndex != -1 && lladdrIndex + 1 < tokens.size) tokens[lladdrIndex + 1] else ""

            if (ip.contains(".") && isValidMac(mac)) {
                return ArpEntry(ip = ip, mac = mac, interfaceName = iface)
            }
        }
        return null
    }

    fun isValidMac(mac: String?): Boolean {
        if (mac.isNullOrBlank()) return false
        val clean = mac.trim().uppercase()
        return clean != "UNKNOWN" &&
                clean != "00:00:00:00:00:00" &&
                clean != "02:00:00:00:00:00" &&
                clean.contains(":") &&
                clean.length >= 11
    }
}
