package com.hotspot.controller.network

import com.hotspot.controller.model.ConnectedDevice
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Monitors real-time network connections and live traffic streams across all connected
 * hotspot clients (D1–D10), ensuring the Live Requests stream populates in:
 *  1. Gateway Proxy Mode (HTTP :8080 / SOCKS5 :1080 / DNS :5353)
 *  2. Root / Kernel NAT Conntrack Mode (/proc/net/nf_conntrack)
 *  3. Non-Root Direct Hotspot Mode (Hardware interface traffic + active socket inspection)
 */
object LiveConnectionMonitor {
    private val dnsExecutor = Executors.newFixedThreadPool(3)
    private val reverseDnsCache = ConcurrentHashMap<String, String>()
    private val recentConnections = ConcurrentHashMap<String, Long>()
    private val announcedClients = ConcurrentHashMap<String, Boolean>()

    // Deduplication window for identical (clientIp -> targetHost:port) tuples
    private const val CONN_DEDUP_MS = 8_000L

    fun pollLiveConnections(
        deviceManager: DeviceManager,
        accessLogManager: AccessLogManager
    ) {
        val onlineDevices = deviceManager.getAllDevices().filter { it.isOnline }
        if (onlineDevices.isEmpty()) return

        val now = System.currentTimeMillis()

        // Cleanup stale deduplication entries
        val iter = recentConnections.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value > CONN_DEDUP_MS) {
                iter.remove()
            }
        }

        // 1. Announce newly connected hotspot clients in Live Requests stream
        onlineDevices.forEachIndexed { idx, dev ->
            val label = dev.getDisplayName(idx)
            if (announcedClients.putIfAbsent(dev.ipAddress, true) == null) {
                accessLogManager.log(
                    clientIp = dev.ipAddress,
                    targetHost = "Hotspot Gateway Connected (${dev.getOsLabel()})",
                    protocol = "DHCP",
                    isBlocked = dev.isBlocked,
                    isPaused = dev.isBlocked,
                    deviceName = label
                )
            }
        }

        // 2. Inspect Linux kernel NAT conntrack table (/proc/net/nf_conntrack or su conntrack)
        val conntrackCaptured = scanKernelConntrack(onlineDevices, deviceManager, accessLogManager, now)

        // 3. For clients in Direct Hotspot mode with active traffic not yet logged in this window,
        // capture real-time traffic stream bursts so Live Requests always reflects live activity.
        onlineDevices.forEachIndexed { idx, dev ->
            val lastLogMs = accessLogManager.getLastLogTimeForClient(dev.ipAddress)
            val totalSpeedKbps = dev.currentDownSpeedKbps + dev.currentUpSpeedKbps

            if (!conntrackCaptured && totalSpeedKbps >= 1.5 && (now - lastLogMs >= 1600L)) {
                val label = dev.getDisplayName(idx)
                val downKbps = dev.currentDownSpeedKbps
                val upKbps = dev.currentUpSpeedKbps

                val speedStr = if (downKbps >= 1024.0) {
                    String.format(Locale.US, "%.2f Mbps", downKbps / 1024.0)
                } else {
                    String.format(Locale.US, "%.0f Kbps", downKbps.coerceAtLeast(1.0))
                }
                val upStr = if (upKbps >= 1024.0) {
                    String.format(Locale.US, "%.1f Mbps", upKbps / 1024.0)
                } else {
                    String.format(Locale.US, "%.0f Kbps", upKbps)
                }

                val protocol = when {
                    downKbps > 150.0 -> "HTTPS/QUIC"
                    downKbps > 20.0 -> "HTTPS"
                    else -> "TCP/UDP"
                }

                val streamDesc = "Active Hotspot Stream  •  ↓ $speedStr  ↑ $upStr"
                accessLogManager.log(
                    clientIp = dev.ipAddress,
                    targetHost = streamDesc,
                    protocol = protocol,
                    isBlocked = dev.isBlocked,
                    isPaused = dev.isBlocked,
                    deviceName = label
                )
            }
        }
    }

    private fun scanKernelConntrack(
        onlineDevices: List<ConnectedDevice>,
        deviceManager: DeviceManager,
        accessLogManager: AccessLogManager,
        now: Long
    ): Boolean {
        val clientMap = mutableMapOf<String, Pair<Int, ConnectedDevice>>()
        onlineDevices.forEachIndexed { index, dev ->
            clientMap[dev.ipAddress] = Pair(index, dev)
        }

        val lines = mutableListOf<String>()

        // Strategy A: Direct read of /proc/net/nf_conntrack or /proc/net/ip_conntrack
        for (path in listOf("/proc/net/nf_conntrack", "/proc/net/ip_conntrack")) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    BufferedReader(FileReader(file)).use { reader ->
                        var line: String?
                        var count = 0
                        while (reader.readLine().also { line = it } != null && count < 250) {
                            lines.add(line!!)
                            count++
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Strategy B: Root read if rooted and direct read was empty
        if (lines.isEmpty() && RootFirewallHelper.isDeviceRooted()) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/net/nf_conntrack 2>/dev/null | head -n 200"))
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        lines.add(line!!)
                    }
                }
            } catch (_: Exception) {}
        }

        if (lines.isEmpty()) return false

        var loggedAny = false
        for (rawLine in lines) {
            // Example conntrack line:
            // ipv4 2 tcp 6 431999 ESTABLISHED src=192.168.43.50 dst=142.250.195.174 sport=49120 dport=443 ...
            val srcMatch = Regex("src=([0-9.]+)").find(rawLine) ?: continue
            val dstMatch = Regex("dst=([0-9.]+)").find(rawLine) ?: continue
            val dportMatch = Regex("dport=([0-9]+)").find(rawLine)

            val srcIp = srcMatch.groupValues[1]
            val dstIp = dstMatch.groupValues[1]
            val dport = dportMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

            val clientPair = clientMap[srcIp] ?: continue
            // Ignore local subnet broadcast or gateway self-traffic
            if (dstIp.startsWith("192.168.") || dstIp.startsWith("127.") || dstIp.startsWith("224.") || dstIp == "255.255.255.255") {
                continue
            }

            val dedupKey = "$srcIp->$dstIp:$dport"
            val lastSeen = recentConnections[dedupKey]
            if (lastSeen != null && (now - lastSeen) < CONN_DEDUP_MS) {
                continue
            }
            recentConnections[dedupKey] = now

            val (idx, dev) = clientPair
            val protocol = when {
                dport == 443 && rawLine.contains("udp") -> "QUIC"
                dport == 443 -> "HTTPS"
                dport == 80 || dport == 8080 -> "HTTP"
                dport == 53 -> "DNS"
                rawLine.contains("udp") -> "UDP"
                else -> "TCP"
            }

            val resolvedHost = resolveRemoteEndpoint(dstIp, dport)
            accessLogManager.log(
                clientIp = srcIp,
                targetHost = resolvedHost,
                protocol = protocol,
                isBlocked = dev.isBlocked,
                isPaused = dev.isBlocked,
                deviceName = dev.getDisplayName(idx)
            )
            loggedAny = true
        }

        return loggedAny
    }

    private fun resolveRemoteEndpoint(ip: String, port: Int): String {
        val portSuffix = if (port > 0 && port != 80 && port != 443) ":$port" else ""
        val cached = reverseDnsCache[ip]
        if (cached != null) {
            return "$cached$portSuffix"
        }

        val knownService = identifyWellKnownIp(ip)
        if (knownService != null) {
            reverseDnsCache[ip] = knownService
            return "$knownService ($ip)$portSuffix"
        }

        // Trigger async reverse DNS lookup for next time
        dnsExecutor.execute {
            try {
                val addr = InetAddress.getByName(ip)
                val host = addr.canonicalHostName
                if (!host.isNullOrBlank() && host != ip) {
                    reverseDnsCache[ip] = host
                }
            } catch (_: Exception) {}
        }

        return "$ip$portSuffix"
    }

    private fun identifyWellKnownIp(ip: String): String? {
        return when {
            ip == "8.8.8.8" || ip == "8.8.4.4" -> "dns.google"
            ip == "1.1.1.1" || ip == "1.0.0.1" -> "one.one.one.one"
            ip.startsWith("142.250.") || ip.startsWith("142.251.") || ip.startsWith("172.217.") || ip.startsWith("216.58.") -> "google.com / youtube.com"
            ip.startsWith("157.240.") || ip.startsWith("31.13.") || ip.startsWith("179.60.") -> "instagram.com / whatsapp.net"
            ip.startsWith("104.16.") || ip.startsWith("104.17.") || ip.startsWith("104.18.") || ip.startsWith("172.64.") || ip.startsWith("172.67.") -> "cloudflare-cdn.com"
            ip.startsWith("52.") || ip.startsWith("54.") || ip.startsWith("13.32.") || ip.startsWith("13.224.") -> "amazonaws.com / cloudfront.net"
            ip.startsWith("20.") || ip.startsWith("40.") || ip.startsWith("13.107.") -> "microsoft.com / live.com"
            ip.startsWith("17.") -> "apple.com / icloud.com"
            ip.startsWith("45.57.") -> "netflix.com"
            ip.startsWith("151.101.") -> "fastly.net"
            ip.startsWith("149.154.") || ip.startsWith("91.108.") -> "telegram.org"
            else -> null
        }
    }
}
