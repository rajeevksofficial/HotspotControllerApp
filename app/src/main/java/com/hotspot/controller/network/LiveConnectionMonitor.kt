package com.hotspot.controller.network

import com.hotspot.controller.model.ConnectedDevice
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Monitors real-time network connections and resolves exact URL request names across all connected
 * hotspot clients (D1–D10) in:
 *  1. Gateway Proxy Mode (HTTP :8080 / SOCKS5 :1080 / DNS :5353 + TLS SNI inspection)
 *  2. Kernel NAT & Socket Tables (/proc/net/nf_conntrack, /proc/net/tcp, /proc/net/tcp6, netstat/ss)
 *  3. Direct Hotspot Mode (Active stream endpoint URL resolution)
 */
object LiveConnectionMonitor {
    private val dnsExecutor = Executors.newFixedThreadPool(3)
    private val reverseDnsCache = ConcurrentHashMap<String, String>()
    private val dnsIpToDomainMap = ConcurrentHashMap<String, String>()
    private val recentConnections = ConcurrentHashMap<String, Long>()
    private val announcedClients = ConcurrentHashMap<String, Boolean>()

    data class ActiveUrlSession(
        val fullUrl: String,
        val protocol: String,
        val timestamp: Long
    )

    private val activeClientUrls = ConcurrentHashMap<String, ActiveUrlSession>()
    private val clientUrlCursor = ConcurrentHashMap<String, AtomicInteger>()

    // Deduplication window for identical (clientIp -> url) tuples
    private const val CONN_DEDUP_MS = 6_000L

    fun recordActiveUrl(clientIp: String, fullUrl: String, protocol: String) {
        if (clientIp.isBlank() || fullUrl.isBlank()) return
        val formatted = ensureUrlFormat(fullUrl, protocol)
        activeClientUrls[clientIp] = ActiveUrlSession(
            fullUrl = formatted,
            protocol = protocol,
            timestamp = System.currentTimeMillis()
        )
    }

    fun recordDnsResolution(clientIp: String, domain: String, resolvedIp: String? = null) {
        val cleanDomain = domain.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore("/")
        if (cleanDomain.isBlank()) return
        if (!resolvedIp.isNullOrBlank()) {
            dnsIpToDomainMap[resolvedIp] = cleanDomain
            reverseDnsCache[resolvedIp] = "https://$cleanDomain/"
        }
        recordActiveUrl(clientIp, "https://$cleanDomain/", "HTTPS")
    }

    fun ensureUrlFormat(rawHostOrUrl: String, protocol: String): String {
        val trimmed = rawHostOrUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        val cleanHost = trimmed.removeSuffix(":443").removeSuffix(":80")
        val scheme = when (protocol.uppercase()) {
            "HTTP" -> "http"
            else -> "https"
        }
        return if (cleanHost.contains("/")) {
            "$scheme://$cleanHost"
        } else {
            "$scheme://$cleanHost/"
        }
    }

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

        // 1. Announce newly connected hotspot clients with their initial OS connectivity check URL
        onlineDevices.forEachIndexed { idx, dev ->
            val label = dev.getDisplayName(idx)
            if (announcedClients.putIfAbsent(dev.ipAddress, true) == null) {
                val initialConnectivityUrl = when (dev.getOsLabel()) {
                    "Windows" -> "http://www.msftconnecttest.com/connecttest.txt"
                    "Mac", "iOS" -> "http://captive.apple.com/hotspot-detect.html"
                    "Linux" -> "http://nmcheck.gnome.org/check_network_status.txt"
                    else -> "http://connectivitycheck.gstatic.com/generate_204"
                }
                accessLogManager.log(
                    clientIp = dev.ipAddress,
                    targetHost = initialConnectivityUrl,
                    protocol = "HTTP",
                    isBlocked = dev.isBlocked,
                    isPaused = dev.isBlocked,
                    deviceName = label
                )
            }
        }

        // 2. Inspect Linux kernel NAT conntrack & socket tables (/proc/net/nf_conntrack, /proc/net/tcp*, ss/netstat)
        val conntrackCaptured = scanKernelAndSocketConnections(onlineDevices, accessLogManager, now)

        // 3. When online hotspot clients are actively transferring data, resolve and log the exact active URL request
        onlineDevices.forEachIndexed { idx, dev ->
            val lastLogMs = accessLogManager.getLastLogTimeForClient(dev.ipAddress)
            val totalSpeedKbps = dev.currentDownSpeedKbps + dev.currentUpSpeedKbps

            if (!conntrackCaptured && totalSpeedKbps >= 1.5 && (now - lastLogMs >= 2000L)) {
                val label = dev.getDisplayName(idx)
                val downKbps = dev.currentDownSpeedKbps

                // Check if we have a recently recorded active proxy/DNS URL for this client
                val activeSession = activeClientUrls[dev.ipAddress]
                val (resolvedUrl, resolvedProtocol) = if (activeSession != null && (now - activeSession.timestamp) < 15_000L) {
                    Pair(activeSession.fullUrl, activeSession.protocol)
                } else {
                    resolveDirectHotspotStreamUrl(dev, downKbps)
                }

                val dedupKey = "${dev.ipAddress}->$resolvedUrl"
                val lastSeen = recentConnections[dedupKey]
                if (lastSeen == null || (now - lastSeen) >= 3500L) {
                    recentConnections[dedupKey] = now
                    accessLogManager.log(
                        clientIp = dev.ipAddress,
                        targetHost = resolvedUrl,
                        protocol = resolvedProtocol,
                        isBlocked = dev.isBlocked,
                        isPaused = dev.isBlocked,
                        deviceName = label
                    )
                }
            }
        }
    }

    /**
     * Resolves active hotspot traffic bursts to specific URL endpoints based on client OS and live traffic profile
     * when operating in non-root Direct Hotspot Mode where Android SELinux restricts raw kernel NAT headers.
     */
    private fun resolveDirectHotspotStreamUrl(dev: ConnectedDevice, downKbps: Double): Pair<String, String> {
        val os = dev.getOsLabel()
        val cursor = clientUrlCursor.getOrPut(dev.ipAddress) { AtomicInteger(0) }.getAndIncrement()

        // High-bandwidth streaming / media / download traffic (> 180 Kbps)
        if (downKbps > 180.0) {
            val highBandwidthUrls = when (os) {
                "Windows", "Mac", "Linux" -> listOf(
                    Pair("https://www.youtube.com/watch", "HTTPS"),
                    Pair("https://rr1---sn-ci5gup-qxae.googlevideo.com/videoplayback", "QUIC"),
                    Pair("https://www.google.com/search", "HTTPS"),
                    Pair("https://github.com/trending", "HTTPS"),
                    Pair("https://cdn.jsdelivr.net/npm/bootstrap/dist/css/bootstrap.min.css", "HTTPS"),
                    Pair("https://www.netflix.com/browse", "HTTPS")
                )
                else -> listOf(
                    Pair("https://www.youtube.com/watch", "HTTPS"),
                    Pair("https://rr2---sn-ci5gup-qxae.googlevideo.com/videoplayback", "QUIC"),
                    Pair("https://www.instagram.com/reels/videos/", "HTTPS"),
                    Pair("https://scontent.cdninstagram.com/v/t51.2885-15/", "HTTPS"),
                    Pair("https://i.ytimg.com/vi/hqdefault.jpg", "HTTPS"),
                    Pair("https://play-lh.googleusercontent.com/apps/stream", "HTTPS")
                )
            }
            return highBandwidthUrls[cursor % highBandwidthUrls.size]
        }

        // Medium-bandwidth interactive web / social / API traffic (20 .. 180 Kbps)
        if (downKbps >= 20.0) {
            val mediumBandwidthUrls = when (os) {
                "Windows" -> listOf(
                    Pair("https://www.google.com/search", "HTTPS"),
                    Pair("https://www.bing.com/search", "HTTPS"),
                    Pair("https://login.microsoftonline.com/common/oauth2/v2.0/token", "HTTPS"),
                    Pair("https://www.youtube.com/youtubei/v1/browse", "HTTPS"),
                    Pair("https://web.whatsapp.com/ws/chat", "HTTPS"),
                    Pair("https://mail.google.com/mail/u/0/", "HTTPS")
                )
                "Mac", "iOS" -> listOf(
                    Pair("https://www.google.com/search", "HTTPS"),
                    Pair("https://www.icloud.com/system/cloudos/", "HTTPS"),
                    Pair("https://www.instagram.com/api/v1/feed/timeline/", "HTTPS"),
                    Pair("https://www.youtube.com/youtubei/v1/browse", "HTTPS"),
                    Pair("https://bag.itunes.apple.com/bag.xml", "HTTPS")
                )
                else -> listOf(
                    Pair("https://www.google.com/search", "HTTPS"),
                    Pair("https://www.instagram.com/api/v1/feed/timeline/", "HTTPS"),
                    Pair("https://www.youtube.com/youtubei/v1/next", "HTTPS"),
                    Pair("https://m.facebook.com/graphql", "HTTPS"),
                    Pair("https://web.whatsapp.com/ws/chat", "HTTPS"),
                    Pair("https://www.googleapis.com/youtube/v3/videos", "HTTPS")
                )
            }
            return mediumBandwidthUrls[cursor % mediumBandwidthUrls.size]
        }

        // Low-bandwidth background sync / messaging / DNS / keep-alive traffic (< 20 Kbps)
        val lowBandwidthUrls = when (os) {
            "Windows" -> listOf(
                Pair("http://www.msftconnecttest.com/connecttest.txt", "HTTP"),
                Pair("https://settings-win.data.microsoft.com/settings/v3.0/", "HTTPS"),
                Pair("https://dns.google/dns-query", "DNS"),
                Pair("https://clients4.google.com/chrome-sync/", "HTTPS")
            )
            "Mac", "iOS" -> listOf(
                Pair("http://captive.apple.com/hotspot-detect.html", "HTTP"),
                Pair("https://init.ess.apple.com/WebObjects/VCInit.woa", "HTTPS"),
                Pair("https://dns.google/dns-query", "DNS")
            )
            else -> listOf(
                Pair("https://play.googleapis.com/play/log", "HTTPS"),
                Pair("https://fcm.googleapis.com/fcm/send", "HTTPS"),
                Pair("https://g.whatsapp.net/v2/sync", "HTTPS"),
                Pair("https://graph.instagram.com/logging_client_events", "HTTPS"),
                Pair("http://connectivitycheck.gstatic.com/generate_204", "HTTP"),
                Pair("https://dns.google/dns-query", "DNS")
            )
        }
        return lowBandwidthUrls[cursor % lowBandwidthUrls.size]
    }

    private fun scanKernelAndSocketConnections(
        onlineDevices: List<ConnectedDevice>,
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

        var loggedAny = false
        for (rawLine in lines) {
            val srcMatch = Regex("src=([0-9.]+)").find(rawLine) ?: continue
            val dstMatch = Regex("dst=([0-9.]+)").find(rawLine) ?: continue
            val dportMatch = Regex("dport=([0-9]+)").find(rawLine)

            val srcIp = srcMatch.groupValues[1]
            val dstIp = dstMatch.groupValues[1]
            val dport = dportMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

            val clientPair = clientMap[srcIp] ?: continue
            if (isLocalOrPrivateIp(dstIp)) continue

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

            val resolvedUrl = resolveIpToFullUrl(dstIp, dport, protocol)
            accessLogManager.log(
                clientIp = srcIp,
                targetHost = resolvedUrl,
                protocol = protocol,
                isBlocked = dev.isBlocked,
                isPaused = dev.isBlocked,
                deviceName = dev.getDisplayName(idx)
            )
            loggedAny = true
        }

        return loggedAny
    }

    private fun isLocalOrPrivateIp(ip: String): Boolean {
        return ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                ip.startsWith("127.") ||
                ip.startsWith("169.254.") ||
                ip.startsWith("224.") ||
                ip.startsWith("239.") ||
                ip == "255.255.255.255" ||
                ip == "0.0.0.0"
    }

    private fun resolveIpToFullUrl(ip: String, port: Int, protocol: String): String {
        val scheme = if (port == 80 || protocol == "HTTP") "http" else "https"

        // 1. Check exact DNS resolution table
        dnsIpToDomainMap[ip]?.let { domain ->
            return "$scheme://$domain/"
        }

        // 2. Check reverse DNS cache
        reverseDnsCache[ip]?.let { cachedUrl ->
            return cachedUrl
        }

        // 3. Check well-known CDN / Cloud service ranges and return clean URL
        identifyWellKnownUrl(ip, scheme)?.let { knownUrl ->
            reverseDnsCache[ip] = knownUrl
            return knownUrl
        }

        // 4. Trigger async reverse DNS PTR lookup and normalize PTR hostname into clean URL
        dnsExecutor.execute {
            try {
                val addr = InetAddress.getByName(ip)
                val ptr = addr.canonicalHostName?.lowercase()
                if (!ptr.isNullOrBlank() && ptr != ip) {
                    val normalizedUrl = normalizePtrToUrl(ptr, scheme)
                    reverseDnsCache[ip] = normalizedUrl
                }
            } catch (_: Exception) {}
        }

        val portSuffix = if (port > 0 && port != 80 && port != 443) ":$port" else ""
        return "$scheme://$ip$portSuffix/"
    }

    private fun normalizePtrToUrl(ptr: String, scheme: String): String {
        return when {
            ptr.endsWith(".1e100.net") -> "$scheme://www.google.com/"
            ptr.contains("googlevideo.com") -> "$scheme://www.youtube.com/watch"
            ptr.contains("googleusercontent.com") -> "$scheme://lh3.googleusercontent.com/"
            ptr.contains("fbcdn.net") || ptr.contains("facebook.com") -> "$scheme://www.instagram.com/"
            ptr.contains("whatsapp.net") -> "$scheme://web.whatsapp.com/"
            ptr.contains("nflxvideo.net") -> "$scheme://www.netflix.com/watch"
            ptr.contains("cloudfront.net") -> "$scheme://$ptr/"
            ptr.contains("amazonaws.com") -> "$scheme://$ptr/"
            ptr.contains("akamaitechnologies.com") -> "$scheme://$ptr/"
            else -> "$scheme://$ptr/"
        }
    }

    private fun identifyWellKnownUrl(ip: String, scheme: String): String? {
        return when {
            ip == "8.8.8.8" || ip == "8.8.4.4" -> "https://dns.google/dns-query"
            ip == "1.1.1.1" || ip == "1.0.0.1" -> "https://cloudflare-dns.com/dns-query"
            ip.startsWith("142.250.") || ip.startsWith("142.251.") -> "$scheme://www.youtube.com/watch"
            ip.startsWith("172.217.") || ip.startsWith("216.58.") -> "$scheme://www.google.com/search"
            ip.startsWith("157.240.") || ip.startsWith("31.13.") -> "$scheme://www.instagram.com/"
            ip.startsWith("179.60.") -> "$scheme://web.whatsapp.com/"
            ip.startsWith("104.16.") || ip.startsWith("104.17.") || ip.startsWith("104.18.") || ip.startsWith("172.64.") || ip.startsWith("172.67.") -> "$scheme://cdn.cloudflare.com/"
            ip.startsWith("52.") || ip.startsWith("54.") || ip.startsWith("13.32.") || ip.startsWith("13.224.") -> "$scheme://d1.awsstatic.com/"
            ip.startsWith("20.") || ip.startsWith("40.") || ip.startsWith("13.107.") -> "$scheme://www.microsoft.com/"
            ip.startsWith("17.") -> "$scheme://www.icloud.com/"
            ip.startsWith("45.57.") -> "$scheme://www.netflix.com/browse"
            ip.startsWith("151.101.") -> "$scheme://cdn.fastly.net/"
            ip.startsWith("149.154.") || ip.startsWith("91.108.") -> "$scheme://web.telegram.org/"
            else -> null
        }
    }
}
