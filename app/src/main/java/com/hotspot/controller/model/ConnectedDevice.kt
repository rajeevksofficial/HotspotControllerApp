package com.hotspot.controller.model

import com.hotspot.controller.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a client device connected to D0's hotspot (D1, D2, D3... up to D10).
 * Can be a Mobile phone, Laptop, PC, Tablet, or Smart device.
 */
data class ConnectedDevice(
    val ipAddress: String,
    var macAddress: String = "Unknown",
    var customName: String = "",
    var resolvedDeviceName: String = "",         // Automatically resolved brand/model (e.g. "Samsung Galaxy S23", "ThinkPad PC")
    var manufacturerVendor: String = "",         // OUI vendor (e.g. "Samsung", "Intel", "Apple", "Dell")
    var deviceCategory: String = "Mobile",       // "Mobile", "Laptop / PC", "Tablet", "Smart TV", "Other"
    var osInfo: String = "",                     // "Android", "Windows 11", "iOS", "macOS", "Linux"
    var isBlocked: Boolean = false,              // Killswitch: completely pause/block internet for this client
    var speedLimitKbps: Int = 0,                 // 0 = Unlimited, otherwise speed cap in Kbps (e.g., 256, 512, 1024, 2048)
    var currentDownSpeedKbps: Double = 0.0,
    var currentUpSpeedKbps: Double = 0.0,
    var totalBytesDownloaded: Long = 0L,
    var totalBytesUploaded: Long = 0L,
    var activeConnections: Int = 0,
    var firstConnectedTimestamp: Long = System.currentTimeMillis(), // Exact time of connection
    var lastSeenTimestamp: Long = System.currentTimeMillis(),
    var isOnline: Boolean = true,                // Real-time connection liveness state
    var isProxyRouted: Boolean = false           // Whether client traffic is routing through controller gateway
) {
    fun getDisplayName(index: Int = 0): String {
        val devTag = "D${index + 1}"
        return if (customName.isNotBlank()) {
            "$devTag • $customName"
        } else {
            devTag
        }
    }

    fun hasValidMac(): Boolean {
        val clean = macAddress.trim().uppercase()
        return clean.isNotBlank() &&
                clean != "UNKNOWN" &&
                clean != "00:00:00:00:00:00" &&
                clean != "02:00:00:00:00:00" &&
                clean.contains(":") &&
                clean.length >= 11
    }

    fun getOsLabel(): String {
        val os = osInfo.trim()
        return when {
            os.contains("Android", ignoreCase = true) -> "Android"
            os.contains("Windows", ignoreCase = true) -> "Windows"
            os.contains("mac", ignoreCase = true) || os.contains("macOS", ignoreCase = true) || os.contains("Apple", ignoreCase = true) -> "Mac"
            os.contains("iOS", ignoreCase = true) || os.contains("iPhone", ignoreCase = true) -> "iOS"
            os.contains("iPad", ignoreCase = true) -> "iPadOS"
            os.contains("Linux", ignoreCase = true) -> "Linux"
            deviceCategory == "Laptop / PC" -> "Windows"
            deviceCategory == "Tablet" -> "Tablet"
            else -> "Android"
        }
    }

    fun getDeviceCategoryIcon(): Int {
        return when (getOsLabel()) {
            "Windows", "Linux" -> R.drawable.ic_laptop
            "Mac" -> R.drawable.ic_laptop
            "Tablet", "iPadOS" -> R.drawable.ic_tablet
            "Android", "iOS" -> R.drawable.ic_phone
            else -> R.drawable.ic_phone
        }
    }

    fun getCategoryLabel(): String = getOsLabel()

    val totalDataMb: Double
        get() = (totalBytesDownloaded + totalBytesUploaded) / (1024.0 * 1024.0)

    val currentTotalSpeedKbps: Double
        get() = currentDownSpeedKbps + currentUpSpeedKbps

    fun getFormattedTotalData(): String {
        val mb = totalDataMb
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    fun getFormattedDataBreakdown(): String {
        val downMb = totalBytesDownloaded / (1024.0 * 1024.0)
        val upMb = totalBytesUploaded / (1024.0 * 1024.0)
        val downStr = if (downMb >= 1024.0) String.format(Locale.US, "%.2f GB", downMb / 1024.0) else String.format(Locale.US, "%.1f MB", downMb)
        val upStr = if (upMb >= 1024.0) String.format(Locale.US, "%.2f GB", upMb / 1024.0) else String.format(Locale.US, "%.1f MB", upMb)
        return "↓ $downStr  •  ↑ $upStr"
    }

    fun getFormattedConnectionTime(): String {
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val timeStr = timeFormat.format(Date(firstConnectedTimestamp))
        val now = System.currentTimeMillis()
        val diffMs = now - firstConnectedTimestamp
        val minutes = (diffMs / (60 * 1000)).coerceAtLeast(0)

        return when {
            minutes < 1 -> "$timeStr (Just now)"
            minutes < 60 -> "$timeStr (${minutes}m ago)"
            else -> {
                val hours = minutes / 60
                val remMin = minutes % 60
                "$timeStr (${hours}h ${remMin}m ago)"
            }
        }
    }
}
