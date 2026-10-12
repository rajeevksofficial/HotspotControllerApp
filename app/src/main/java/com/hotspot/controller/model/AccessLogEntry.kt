package com.hotspot.controller.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Log entry capturing client HTTP/HTTPS/SOCKS5 requests with live status.
 */
data class AccessLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val clientIp: String,
    val deviceName: String = "",
    val targetHost: String,
    val protocol: String = "HTTPS",
    val isBlocked: Boolean = false,
    val isPaused: Boolean = false,
    val ruleMatched: String? = null
) {
    val statusText: String
        get() = when {
            isPaused -> "PAUSED"
            isBlocked -> "BLOCKED"
            else -> "ALLOWED"
        }

    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
