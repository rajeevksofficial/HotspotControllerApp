package com.hotspot.controller.network

import com.hotspot.controller.model.AccessLogEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

class AccessLogManager {
    private val maxLogs = 500
    private val logs = ConcurrentLinkedDeque<AccessLogEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val lastClientLogTimestamp = ConcurrentHashMap<String, Long>()

    @Volatile var totalBlockedCount: Int = 0
        private set
    @Volatile var totalAllowedCount: Int = 0
        private set
    @Volatile var totalPausedCount: Int = 0
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
        val now = System.currentTimeMillis()
        lastClientLogTimestamp[clientIp] = now

        val entry = AccessLogEntry(
            timestamp = now,
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

    fun getLastLogTimeForClient(clientIp: String): Long {
        return lastClientLogTimestamp[clientIp] ?: 0L
    }

    fun getLogs(): List<AccessLogEntry> {
        return logs.toList()
    }

    fun clear() {
        logs.clear()
        lastClientLogTimestamp.clear()
        totalBlockedCount = 0
        totalAllowedCount = 0
        totalPausedCount = 0
        notifyListeners()
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            try {
                listener.invoke()
            } catch (_: Exception) {}
        }
    }
}
