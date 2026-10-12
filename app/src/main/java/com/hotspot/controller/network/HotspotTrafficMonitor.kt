package com.hotspot.controller.network

import android.net.TrafficStats
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

data class InterfaceStats(
    val rxBytes: Long,
    val txBytes: Long
)

object HotspotTrafficMonitor {
    private const val TAG = "HotspotTrafficMonitor"

    private var lastRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastCheckTimeMs: Long = System.currentTimeMillis()

    @Volatile var liveDownSpeedKbps: Double = 0.0
        private set
    @Volatile var liveUpSpeedKbps: Double = 0.0
        private set
    @Volatile var totalDownBytes: Long = 0L
        private set
    @Volatile var totalUpBytes: Long = 0L
        private set

    /**
     * Reads real-time hardware transfer stats from /proc/net/dev or /sys/class/net/.
     * Calculates live download & upload speed (Kbps) across the hotspot interface.
     */
    fun updateLiveInterfaceSpeed() {
        val now = System.currentTimeMillis()
        val elapsedMs = now - lastCheckTimeMs
        if (elapsedMs < 800) return // Measure at roughly ~1 second intervals

        val stats = readHotspotInterfaceBytes()
        if (stats != null) {
            if (lastRxBytes > 0 && lastTxBytes > 0 && elapsedMs > 0) {
                // On AP interface:
                // txBytes = Data transmitted from D0 to client devices (Client DOWNLOAD)
                // rxBytes = Data received by D0 from client devices (Client UPLOAD)
                val diffTx = (stats.txBytes - lastTxBytes).coerceAtLeast(0)
                val diffRx = (stats.rxBytes - lastRxBytes).coerceAtLeast(0)

                liveDownSpeedKbps = (diffTx * 8.0) / elapsedMs
                liveUpSpeedKbps = (diffRx * 8.0) / elapsedMs

                totalDownBytes += diffTx
                totalUpBytes += diffRx
            }

            lastTxBytes = stats.txBytes
            lastRxBytes = stats.rxBytes
            lastCheckTimeMs = now
        } else {
            // Fallback: global mobile/wifi delta if specific interface not found
            updateFallbackStats(now, elapsedMs)
        }
    }

    private fun readHotspotInterfaceBytes(): InterfaceStats? {
        val hotspotIface = HotspotNetworkHelper.getHotspotInterfaceName() ?: "ap0"

        // 1. Try reading /sys/class/net/<iface>/statistics/
        try {
            val rxFile = File("/sys/class/net/$hotspotIface/statistics/rx_bytes")
            val txFile = File("/sys/class/net/$hotspotIface/statistics/tx_bytes")
            if (rxFile.exists() && txFile.exists()) {
                val rx = rxFile.readText().trim().toLongOrNull() ?: 0L
                val tx = txFile.readText().trim().toLongOrNull() ?: 0L
                return InterfaceStats(rxBytes = rx, txBytes = tx)
            }
        } catch (_: Exception) {}

        // 2. Try parsing /proc/net/dev
        try {
            val devFile = File("/proc/net/dev")
            if (devFile.exists() && devFile.canRead()) {
                BufferedReader(FileReader(devFile)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line?.trim() ?: continue
                        if (text.startsWith(hotspotIface) || text.startsWith("ap") || text.startsWith("wlan1") || text.startsWith("softap")) {
                            val tokens = text.substringAfter(":").trim().split(Regex("\\s+"))
                            if (tokens.size >= 9) {
                                val rx = tokens[0].toLongOrNull() ?: 0L
                                val tx = tokens[8].toLongOrNull() ?: 0L
                                return InterfaceStats(rxBytes = rx, txBytes = tx)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun updateFallbackStats(now: Long, elapsedMs: Long) {
        val totalRx = TrafficStats.getTotalRxBytes()
        val totalTx = TrafficStats.getTotalTxBytes()

        if (totalRx != TrafficStats.UNSUPPORTED.toLong() && totalTx != TrafficStats.UNSUPPORTED.toLong()) {
            if (lastRxBytes > 0 && lastTxBytes > 0 && elapsedMs > 0) {
                val diffRx = (totalRx - lastRxBytes).coerceAtLeast(0)
                val diffTx = (totalTx - lastTxBytes).coerceAtLeast(0)

                // In tethering mode, incoming cellular rx is forwarded as tx to hotspot
                liveDownSpeedKbps = (diffRx * 8.0) / elapsedMs
                liveUpSpeedKbps = (diffTx * 8.0) / elapsedMs

                totalDownBytes += diffRx
                totalUpBytes += diffTx
            }
            lastRxBytes = totalRx
            lastTxBytes = totalTx
            lastCheckTimeMs = now
        }
    }
}
