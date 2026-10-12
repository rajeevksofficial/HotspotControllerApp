package com.hotspot.controller.network

import java.io.InputStream
import java.io.OutputStream

object ThrottledStreams {

    /**
     * Dynamically chooses optimal buffer size based on speed limit.
     * At 256 Kbps (32 KB/s), a 1024-byte buffer ensures smooth, low-latency packets
     * without long stalls or burst spikes.
     */
    fun getOptimalBufferSize(speedLimitKbps: Int): Int {
        return when {
            speedLimitKbps in 1..256 -> 1024
            speedLimitKbps in 257..512 -> 2048
            speedLimitKbps in 513..1536 -> 4096
            else -> 8192
        }
    }

    /**
     * Pipes data from [input] to [output] while applying per-device throttler and global throttler.
     * Continuously checks [isBlockedProvider] to sever connection immediately if internet is paused.
     * Invokes [onBytesTransferred] on each chunk.
     */
    fun pipe(
        input: InputStream,
        output: OutputStream,
        deviceThrottler: BandwidthThrottler?,
        globalThrottler: BandwidthThrottler?,
        isBlockedProvider: () -> Boolean,
        onBytesTransferred: (Int) -> Unit
    ) {
        val limit = deviceThrottler?.rateLimitKbps ?: 0
        val bufferSize = getOptimalBufferSize(limit)
        val buffer = ByteArray(bufferSize)
        var read: Int

        try {
            while (true) {
                if (isBlockedProvider()) {
                    try { output.close() } catch (_: Exception) {}
                    try { input.close() } catch (_: Exception) {}
                    break
                }

                read = input.read(buffer)
                if (read == -1) break

                if (isBlockedProvider()) {
                    try { output.close() } catch (_: Exception) {}
                    try { input.close() } catch (_: Exception) {}
                    break
                }

                // Apply device-specific bandwidth cap with instant abort support
                deviceThrottler?.throttle(read, shouldAbort = isBlockedProvider)

                // Apply overall hotspot bandwidth cap with instant abort support
                globalThrottler?.throttle(read, shouldAbort = isBlockedProvider)

                if (isBlockedProvider()) {
                    try { output.close() } catch (_: Exception) {}
                    try { input.close() } catch (_: Exception) {}
                    break
                }

                output.write(buffer, 0, read)
                output.flush()

                onBytesTransferred(read)
            }
        } catch (_: Exception) {
            // Socket closed or connection reset by peer
        } finally {
            try { output.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
        }
    }
}
