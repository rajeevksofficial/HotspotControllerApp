package com.hotspot.controller.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

data class DeviceResolutionResult(
    val deviceName: String,
    val deviceCategory: String, // "Mobile", "Laptop / PC", "Tablet", "Smart TV", "Other"
    val osInfo: String = ""
)

object DeviceNameResolver {
    private const val TAG = "DeviceNameResolver"
    private val executor = Executors.newFixedThreadPool(4)

    // MAC OUI prefixes mapped to Brand, Category, and OS
    // Triple(VendorName, DeviceCategory, OS)
    private val MAC_VENDORS = mapOf(
        // Apple Devices
        "F01898" to Triple("Apple iPhone", "Mobile", "iOS"),
        "3C22FB" to Triple("Apple iPhone", "Mobile", "iOS"),
        "ACBC32" to Triple("Apple iPhone", "Mobile", "iOS"),
        "F40F24" to Triple("Apple iPhone", "Mobile", "iOS"),
        "DC2B61" to Triple("Apple iPhone", "Mobile", "iOS"),
        "BC0358" to Triple("Apple iPad", "Tablet", "iPadOS"),
        "286AB8" to Triple("Apple iPad", "Tablet", "iPadOS"),
        "D88039" to Triple("Apple Device", "Mobile", "iOS"),
        "000393" to Triple("Apple Mac", "Laptop / PC", "Mac"),
        "000502" to Triple("Apple Mac", "Laptop / PC", "Mac"),
        "0010FA" to Triple("Apple Mac", "Laptop / PC", "Mac"),
        "14109F" to Triple("Apple Mac", "Laptop / PC", "Mac"),
        "3C0754" to Triple("Apple Mac", "Laptop / PC", "Mac"),
        "600308" to Triple("Apple Mac", "Laptop / PC", "Mac"),

        // Samsung
        "BCD074" to Triple("Samsung Galaxy", "Mobile", "Android"),
        "2C200B" to Triple("Samsung Galaxy", "Mobile", "Android"),
        "48D705" to Triple("Samsung Galaxy", "Mobile", "Android"),
        "9401C2" to Triple("Samsung Galaxy", "Mobile", "Android"),
        "A0821F" to Triple("Samsung Galaxy", "Mobile", "Android"),
        "78471D" to Triple("Samsung Galaxy", "Mobile", "Android"),

        // Laptops & PCs (Intel, Realtek, Dell, HP, Lenovo, Asus, Acer, Microsoft)
        "001B21" to Triple("Intel PC", "Laptop / PC", "Windows"),
        "00216B" to Triple("Intel PC", "Laptop / PC", "Windows"),
        "3CFDFE" to Triple("Intel PC", "Laptop / PC", "Windows"),
        "8086F2" to Triple("Intel PC", "Laptop / PC", "Windows"),
        "A4BB6D" to Triple("Intel PC", "Laptop / PC", "Windows"),
        "00E04C" to Triple("Realtek PC", "Laptop / PC", "Windows"),
        "525400" to Triple("Realtek PC", "Laptop / PC", "Windows"),
        "001422" to Triple("Dell PC", "Laptop / PC", "Windows"),
        "14FEB5" to Triple("Dell PC", "Laptop / PC", "Windows"),
        "3417EB" to Triple("Dell PC", "Laptop / PC", "Windows"),
        "0001E6" to Triple("HP PC", "Laptop / PC", "Windows"),
        "001E0B" to Triple("HP PC", "Laptop / PC", "Windows"),
        "308D99" to Triple("HP PC", "Laptop / PC", "Windows"),
        "001A6B" to Triple("Lenovo PC", "Laptop / PC", "Windows"),
        "AC3870" to Triple("Lenovo PC", "Laptop / PC", "Windows"),
        "54EE75" to Triple("ThinkPad PC", "Laptop / PC", "Windows"),
        "001A92" to Triple("ASUS PC", "Laptop / PC", "Windows"),
        "04D9F5" to Triple("ASUS PC", "Laptop / PC", "Windows"),
        "006067" to Triple("Acer PC", "Laptop / PC", "Windows"),
        "00125A" to Triple("Surface PC", "Laptop / PC", "Windows"),

        // Mobile Brands
        "F4F5DB" to Triple("OnePlus Device", "Mobile", "Android"),
        "508A06" to Triple("OnePlus Device", "Mobile", "Android"),
        "2479F3" to Triple("OnePlus Device", "Mobile", "Android"),
        "A4C3F0" to Triple("Xiaomi / Redmi", "Mobile", "Android"),
        "7404F1" to Triple("Xiaomi / Redmi", "Mobile", "Android"),
        "18473D" to Triple("Xiaomi / Redmi", "Mobile", "Android"),
        "582429" to Triple("Xiaomi / Redmi", "Mobile", "Android"),
        "68DBCA" to Triple("Xiaomi / Redmi", "Mobile", "Android"),
        "001A11" to Triple("Google Pixel", "Mobile", "Android"),
        "3C5AB4" to Triple("Google Pixel", "Mobile", "Android"),
        "546009" to Triple("Google Pixel", "Mobile", "Android"),
        "702C1F" to Triple("Motorola Phone", "Mobile", "Android"),
        "F83880" to Triple("Motorola Phone", "Mobile", "Android"),
        "B88687" to Triple("Vivo Phone", "Mobile", "Android"),
        "88299C" to Triple("Vivo Phone", "Mobile", "Android"),
        "983B8F" to Triple("Realme / Oppo", "Mobile", "Android"),
        "D88F76" to Triple("Realme / Oppo", "Mobile", "Android")
    )

    /**
     * Resolves device brand, type (Mobile, Laptop/PC, Tablet), and OS asynchronously.
     */
    fun resolveDeviceNameAsync(
        ip: String,
        mac: String?,
        onResolved: (DeviceResolutionResult) -> Unit
    ) {
        executor.execute {
            // 1. Try NetBIOS Name Query (UDP 137) - Signifies Windows PC
            val netBiosName = queryNetBiosName(ip)
            if (!netBiosName.isNullOrBlank()) {
                val categorized = categorizeFromHostname(netBiosName)
                onResolved(categorized)
                return@execute
            }

            // 2. Active TCP SMB probe (port 445) - Characteristic of Windows
            try {
                val smbSocket = java.net.Socket()
                smbSocket.connect(java.net.InetSocketAddress(ip, 445), 150)
                smbSocket.close()
                onResolved(DeviceResolutionResult("", "Laptop / PC", "Windows"))
                return@execute
            } catch (_: Exception) {}

            // 3. Try Reverse DNS
            val dnsHost = queryReverseDns(ip)
            if (!dnsHost.isNullOrBlank()) {
                val categorized = categorizeFromHostname(dnsHost)
                onResolved(categorized)
                return@execute
            }

            // 4. Try MAC Address OUI lookup
            if (!mac.isNullOrBlank()) {
                val macResult = lookupMacVendorInfo(mac)
                if (macResult != null) {
                    onResolved(macResult)
                    return@execute
                }
            }

            // 5. Default fallback for mobile hotspot client: Android
            onResolved(DeviceResolutionResult("", "Mobile", "Android"))
        }
    }

    /**
     * Parses HTTP User-Agent string to classify device and extract OS & model.
     */
    fun parseUserAgent(userAgent: String): DeviceResolutionResult? {
        val ua = userAgent.lowercase()
        return when {
            // Windows PC / Laptop
            ua.contains("windows") -> {
                val os = when {
                    ua.contains("windows nt 10.0") -> "Windows 10/11"
                    ua.contains("windows nt 6.3") -> "Windows 8.1"
                    ua.contains("windows nt 6.1") -> "Windows 7"
                    else -> "Windows"
                }
                DeviceResolutionResult(
                    deviceName = "$os PC",
                    deviceCategory = "Laptop / PC",
                    osInfo = os
                )
            }

            // Apple Mac / MacBook
            ua.contains("macintosh") || ua.contains("mac os x") -> {
                DeviceResolutionResult(
                    deviceName = "Apple Mac",
                    deviceCategory = "Laptop / PC",
                    osInfo = "macOS"
                )
            }

            // Linux Desktop / Laptop
            (ua.contains("x11") || ua.contains("linux")) && !ua.contains("android") -> {
                val distro = when {
                    ua.contains("ubuntu") -> "Ubuntu Linux"
                    ua.contains("fedora") -> "Fedora Linux"
                    ua.contains("debian") -> "Debian Linux"
                    else -> "Linux PC"
                }
                DeviceResolutionResult(
                    deviceName = distro,
                    deviceCategory = "Laptop / PC",
                    osInfo = "Linux"
                )
            }

            // Apple iPad
            ua.contains("ipad") -> {
                DeviceResolutionResult(
                    deviceName = "Apple iPad",
                    deviceCategory = "Tablet",
                    osInfo = "iPadOS"
                )
            }

            // Apple iPhone
            ua.contains("iphone") -> {
                DeviceResolutionResult(
                    deviceName = "Apple iPhone",
                    deviceCategory = "Mobile",
                    osInfo = "iOS"
                )
            }

            // Smart TV
            ua.contains("smart-tv") || ua.contains("smarttv") || ua.contains("tizen") || ua.contains("webos") || ua.contains("roku") || ua.contains("appletv") || ua.contains("firetv") -> {
                val tvBrand = when {
                    ua.contains("tizen") -> "Samsung Smart TV"
                    ua.contains("webos") -> "LG Smart TV"
                    ua.contains("roku") -> "Roku TV"
                    ua.contains("firetv") -> "Amazon Fire TV"
                    ua.contains("appletv") -> "Apple TV"
                    else -> "Smart TV"
                }
                DeviceResolutionResult(
                    deviceName = tvBrand,
                    deviceCategory = "Smart TV",
                    osInfo = "Smart TV"
                )
            }

            // Android Devices (Tablets & Phones)
            ua.contains("android") -> {
                val isTablet = ua.contains("tablet") || (!ua.contains("mobile") && (ua.contains("sm-t") || ua.contains("sm-x") || ua.contains("tab")))
                val category = if (isTablet) "Tablet" else "Mobile"

                val model = when {
                    ua.contains("sm-s92") -> "Galaxy S24"
                    ua.contains("sm-s91") -> "Galaxy S23"
                    ua.contains("sm-s90") -> "Galaxy S22"
                    ua.contains("sm-g9") -> "Galaxy S Series"
                    ua.contains("sm-a") -> "Galaxy A Series"
                    ua.contains("sm-m") -> "Galaxy M Series"
                    ua.contains("sm-f") -> "Galaxy Z Fold/Flip"
                    ua.contains("sm-t") || ua.contains("sm-x") -> "Galaxy Tab"
                    ua.contains("pixel 8") -> "Google Pixel 8"
                    ua.contains("pixel 7") -> "Google Pixel 7"
                    ua.contains("pixel 6") -> "Google Pixel 6"
                    ua.contains("pixel") -> "Google Pixel"
                    ua.contains("redmi") -> "Xiaomi Redmi"
                    ua.contains("poco") -> "Xiaomi POCO"
                    ua.contains("xiaomi") || ua.contains("mi ") -> "Xiaomi Device"
                    ua.contains("oneplus") || ua.contains("cph2") -> "OnePlus Phone"
                    ua.contains("vivo") -> "Vivo Phone"
                    ua.contains("oppo") -> "Oppo Phone"
                    ua.contains("realme") -> "Realme Phone"
                    ua.contains("moto") -> "Motorola Phone"
                    else -> {
                        val match = Regex("android\\s+[\\d.]+;\\s*([a-zA-Z0-9_\\-\\s]+?)(?:\\s+build|;|\\))").find(ua)
                        match?.groupValues?.getOrNull(1)?.trim()?.replaceFirstChar { it.uppercase() } ?: if (isTablet) "Tablet" else "Android"
                    }
                }

                DeviceResolutionResult(
                    deviceName = model,
                    deviceCategory = category,
                    osInfo = "Android"
                )
            }

            else -> null
        }
    }

    fun inferOsFromDomain(domain: String): String? {
        val d = domain.lowercase()
        return when {
            d.contains("msftconnecttest") || d.contains("microsoft") || d.contains("windowsupdate") || d.contains("msn.com") || d.contains("live.com") || d.contains("office.com") || d.contains("bing.com") -> "Windows"
            d.contains("apple.com") || d.contains("icloud.com") || d.contains("aaplimg.com") || d.contains("apple-dns.net") -> "Mac"
            d.contains("gstatic.com") || d.contains("googleapis.com") || d.contains("android.clients.google") || d.contains("1e100.net") || d.contains("ggpht.com") -> "Android"
            else -> null
        }
    }

    fun categorizeFromHostname(hostname: String): DeviceResolutionResult {
        val h = hostname.lowercase()
        return when {
            h.contains("laptop") || h.contains("desktop") || h.contains("pc") || h.startsWith("win-") || h.contains("thinkpad") || h.contains("surface") || h.contains("macbook") -> {
                val os = if (h.contains("macbook")) "Mac" else "Windows"
                DeviceResolutionResult(hostname, "Laptop / PC", os)
            }
            h.contains("ipad") || h.contains("tab") -> {
                val os = if (h.contains("ipad")) "iPadOS" else "Android"
                DeviceResolutionResult(hostname, "Tablet", os)
            }
            h.contains("tv") || h.contains("fire") || h.contains("roku") || h.contains("cast") -> {
                DeviceResolutionResult(hostname, "Smart TV", "Smart TV")
            }
            h.contains("iphone") -> {
                DeviceResolutionResult(hostname, "Mobile", "iOS")
            }
            h.contains("android") || h.contains("galaxy") || h.contains("pixel") || h.contains("redmi") || h.contains("poco") -> {
                DeviceResolutionResult(hostname, "Mobile", "Android")
            }
            else -> {
                DeviceResolutionResult(hostname, "Mobile", "Android")
            }
        }
    }

    fun lookupMacVendorInfo(mac: String): DeviceResolutionResult? {
        val clean = mac.replace(":", "").replace("-", "").uppercase()
        if (clean.length >= 6) {
            val prefix = clean.substring(0, 6)
            val info = MAC_VENDORS[prefix]
            if (info != null) {
                return DeviceResolutionResult(
                    deviceName = info.first,
                    deviceCategory = info.second,
                    osInfo = info.third
                )
            }
        }
        return null
    }

    fun lookupMacVendor(mac: String): String? {
        return lookupMacVendorInfo(mac)?.deviceName
    }

    private fun queryNetBiosName(ip: String): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 250

            val queryBytes = byteArrayOf(
                0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x20.toByte(), 0x43.toByte(), 0x4B.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x41.toByte(), 0x00.toByte(), 0x00.toByte(), 0x21.toByte(), 0x00.toByte(),
                0x01.toByte()
            )

            val packet = DatagramPacket(queryBytes, queryBytes.size, InetAddress.getByName(ip), 137)
            socket.send(packet)

            val buf = ByteArray(1024)
            val recvPacket = DatagramPacket(buf, buf.size)
            socket.receive(recvPacket)

            val data = recvPacket.data
            if (recvPacket.length > 57) {
                val numNames = data[56].toInt() and 0xFF
                if (numNames > 0 && recvPacket.length >= 57 + 18) {
                    val rawName = String(data, 57, 15).trim()
                    if (rawName.isNotBlank() && !rawName.startsWith("IS~") && !rawName.contains("\u0000")) {
                        return cleanHostname(rawName)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
        return null
    }

    private fun queryReverseDns(ip: String): String? {
        return try {
            val host = InetAddress.getByName(ip).hostName
            if (host != ip && host.isNotBlank()) {
                cleanHostname(host)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanHostname(raw: String): String {
        return raw.trim()
            .removeSuffix(".lan")
            .removeSuffix(".local")
            .removeSuffix(".home")
            .replace("-", " ")
            .replace("_", " ")
    }
}
