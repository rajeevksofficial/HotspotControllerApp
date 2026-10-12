package com.hotspot.controller.network

import com.hotspot.controller.model.AccessLogEntry
import java.util.concurrent.ConcurrentLinkedDeque

class AccessLogManager {
    private val maxLogs = 500
    private val logs = ConcurrentLinkedDeque<AccessLogEntry>()
    private val listeners = mutableListOf<() -> Unit>()

    var totalBlockedCount: Int = 0
        private set
    var totalAllowedCount: Int = 0
        private set
    var totalPausedCount: Int = 0
        private set

    fun log(
        clientIp: String,
        targetHost: String,
        protocol: String,
        isBlocked: Boolean,
        isPaused: Boolean = false,
        ruleMatched: String? = null,
        deviceName: String = ""
    ) {
        val entry = AccessLogEntry(
            clientIp = clientIp,
            deviceName = deviceName,
            targetHost = targetHost,
            protocol = protocol,
            isBlocked = isBlocked,
            isPaused = isPaused,
            ruleMatched = ruleMatched
        )

        when {
            isPaused -> totalPausedCount++
            isBlocked -> totalBlockedCount++
            else -> totalAllowedCount++
        }

        logs.addFirst(entry)
        while (logs.size > maxLogs) {
            logs.removeLast()
        }

        notifyListeners()
    }

    fun getLogs(): List<AccessLogEntry> {
        return logs.toList()
    }

    fun clear() {
        logs.clear()
        totalBlockedCount = 0
        totalAllowedCount = 0
        totalPausedCount = 0
        notifyListeners()
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }
}
