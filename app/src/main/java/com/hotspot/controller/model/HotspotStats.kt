package com.hotspot.controller.model

/**
 * Real-time overall hotspot telemetry data.
 */
data class HotspotStats(
    val isRunning: Boolean = false,
    val serverIp: String = "192.168.43.1",
    val serverPort: Int = 8080,
    val connectedClientsCount: Int = 0,
    val maxDeviceLimit: Int = 10,
    val totalDownloadSpeedKbps: Double = 0.0,
    val totalUploadSpeedKbps: Double = 0.0,
    val totalBytesDownloaded: Long = 0L,
    val totalBytesUploaded: Long = 0L,
    val blockedRequestsCount: Int = 0,
    val allowedRequestsCount: Int = 0,
    val isGlobalSpeedLimitActive: Boolean = false,
    val globalSpeedLimitKbps: Int = 0
)
