package com.hotspot.controller.network

import kotlin.math.max

/**
 * High-precision, multi-thread safe Rate Limiter with Time-Slice Reservation.
 * Guarantees that cumulative throughput across ALL concurrent sockets and threads
 * is strictly capped at [rateLimitKbps] (kilobits per second).
 *
 * 0 means Unlimited.
 */
class BandwidthThrottler(
    @Volatile var rateLimitKbps: Int = 0 // 0 = Unlimited
) {
    private val lock = Any()
    private var nextAllowedTimeNano: Long = System.nanoTime()

    // Real-time speed measurement in rolling 1-second window
    private var windowBytes: Long = 0L
    private var windowStartTimeMs: Long = System.currentTimeMillis()
    @Volatile var currentSpeedKbps: Double = 0.0
        private set
    @Volatile var totalBytesTransferred: Long = 0L
        private set

    fun reset() {
        synchronized(lock) {
            nextAllowedTimeNano = System.nanoTime()
            windowBytes = 0L
            windowStartTimeMs = System.currentTimeMillis()
            currentSpeedKbps = 0.0
            totalBytesTransferred = 0L
        }
    }

    /**
     * Consumes [bytesCount] bytes and applies precise time-slice reservation.
     * When multiple threads call this concurrently, each thread reserves its future time slot
     * so that the combined speed CANNOT exceed [rateLimitKbps].
     *
     * [shouldAbort] is polled during sleep so that if internet pause is activated,
     * the thread wakes up immediately within <= 15ms and aborts without delay.
     */
    fun throttle(bytesCount: Int, shouldAbort: () -> Boolean = { false }) {
        if (bytesCount <= 0) return

        val limit = rateLimitKbps
        val nowMs = System.currentTimeMillis()

        synchronized(lock) {
            totalBytesTransferred += bytesCount
            windowBytes += bytesCount

            val windowElapsedMs = nowMs - windowStartTimeMs
            if (windowElapsedMs >= 1000) {
                currentSpeedKbps = (windowBytes * 8.0) / windowElapsedMs
                windowBytes = 0L
                windowStartTimeMs = nowMs
            }
        }

        if (limit <= 0) {
            return // Unlimited bandwidth
        }

        val bytesPerSec = (limit.toDouble() * 1024.0) / 8.0
        // Duration this chunk of bytes occupies on the wire at configured rate
        val chunkDurationNanos = ((bytesCount.toDouble() / bytesPerSec) * 1_000_000_000.0).toLong()

        var waitNanos = 0L

        synchronized(lock) {
            val nowNano = System.nanoTime()

            // Allow at most 25ms burst after idle periods
            val maxCatchupWindowNano = 25_000_000L
            if (nextAllowedTimeNano < nowNano - maxCatchupWindowNano) {
                nextAllowedTimeNano = nowNano - maxCatchupWindowNano
            }

            val scheduledStartTimeNano = max(nowNano, nextAllowedTimeNano)
            nextAllowedTimeNano = scheduledStartTimeNano + chunkDurationNanos

            waitNanos = scheduledStartTimeNano - nowNano
        }

        // Sleep outside lock in small 15ms slices to allow instant abort on internet pause
        if (waitNanos > 0) {
            val deadlineNano = System.nanoTime() + waitNanos
            while (true) {
                if (shouldAbort()) break
                val remainingNanos = deadlineNano - System.nanoTime()
                if (remainingNanos <= 0) break

                val sleepMillis = (remainingNanos / 1_000_000L).coerceAtMost(15L)
                val sleepNanosPart = if (sleepMillis == 0L) (remainingNanos % 1_000_000L).toInt() else 0
                try {
                    if (sleepMillis > 0 || sleepNanosPart > 0) {
                        Thread.sleep(sleepMillis, sleepNanosPart)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    /**
     * Periodic decay for speed calculation when idle.
     */
    fun checkIdleDecay() {
        synchronized(lock) {
            val elapsed = System.currentTimeMillis() - windowStartTimeMs
            if (elapsed > 1200) {
                currentSpeedKbps = 0.0
                windowBytes = 0L
                windowStartTimeMs = System.currentTimeMillis()
            }
        }
    }
}
