package com.hotspot.controller.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local DNS sinkhole resolver that intercepts DNS queries on UDP [port].
 * Allows instant blocking of URLs/domains at the DNS level (Pi-hole architecture).
 */
class DnsSinkholeServer(
    val port: Int = 5353,
    private val deviceManager: DeviceManager,
    private val urlRuleManager: UrlRuleManager,
    private val accessLogManager: AccessLogManager
) {
    companion object {
        private const val TAG = "DnsSinkholeServer"
        private val UPSTREAM_DNS = InetAddress.getByName("8.8.8.8")
        private const val UPSTREAM_PORT = 53
    }

    private var socket: DatagramSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        executor.execute {
            try {
                socket = DatagramSocket(port)
                socket?.soTimeout = 2000
                Log.i(TAG, "DNS Sinkhole Server started on UDP port $port")

                val buffer = ByteArray(1024)
                while (isRunning.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)

                        val clientIp = packet.address?.hostAddress ?: "Unknown"
                        val clientPort = packet.port
                        val requestData = packet.data.copyOf(packet.length)

                        executor.execute {
                            handleDnsQuery(clientIp, clientPort, requestData)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Loop to check isRunning
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.e(TAG, "DNS receive error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind DNS server on port $port: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        Log.i(TAG, "DNS Sinkhole Server stopped.")
    }

    fun isServerRunning(): Boolean = isRunning.get()

    private fun handleDnsQuery(clientIp: String, clientPort: Int, queryData: ByteArray) {
        if (queryData.size < 12) return

        // Register client device in manager upon DNS packet
        val device = deviceManager.getOrCreateDevice(clientIp)
        val domain = extractDomainFromDnsQuery(queryData) ?: return

        // Infer OS from domain (e.g. msftconnecttest -> Windows, connectivitycheck -> Android)
        val inferredOs = DeviceNameResolver.inferOsFromDomain(domain)
        if (inferredOs != null) {
            deviceManager.updateDeviceOs(clientIp, inferredOs)
        }

        if (device.isBlocked) {
            accessLogManager.log(
                clientIp = clientIp,
                targetHost = domain,
                protocol = "DNS",
                isBlocked = true,
                isPaused = true,
                ruleMatched = "Device Paused",
                deviceName = deviceManager.getDeviceDisplayName(clientIp)
            )
            sendBlockedResponse(clientIp, clientPort, queryData)
            return
        }

        val (isBlocked, ruleMatched) = urlRuleManager.isHostBlocked(domain)
        accessLogManager.log(
            clientIp = clientIp,
            targetHost = domain,
            protocol = "DNS",
            isBlocked = isBlocked,
            isPaused = false,
            ruleMatched = ruleMatched,
            deviceName = deviceManager.getDeviceDisplayName(clientIp)
        )

        if (isBlocked) {
            sendBlockedResponse(clientIp, clientPort, queryData)
        } else {
            forwardToUpstream(clientIp, clientPort, queryData)
        }
    }

    /**
     * Extracts domain name from DNS question section.
     */
    private fun extractDomainFromDnsQuery(data: ByteArray): String? {
        try {
            var pos = 12
            val sb = StringBuilder()
            while (pos < data.size) {
                val len = data[pos].toInt() and 0xFF
                if (len == 0) break
                pos++
                if (pos + len > data.size) return null
                if (sb.isNotEmpty()) sb.append(".")
                for (i in 0 until len) {
                    sb.append(data[pos + i].toInt().toChar())
                }
                pos += len
            }
            return sb.toString().lowercase()
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Returns 0.0.0.0 (Sinkhole) response for blocked domains.
     */
    private fun sendBlockedResponse(clientIp: String, clientPort: Int, query: ByteArray) {
        try {
            val response = query.clone()
            // Flags: Standard query response, No error
            response[2] = 0x81.toByte()
            response[3] = 0x80.toByte()
            // Questions: 1, Answers: 1
            response[6] = 0x00.toByte()
            response[7] = 0x01.toByte()

            // Find end of question section
            var pos = 12
            while (pos < response.size && response[pos].toInt() != 0) {
                pos += (response[pos].toInt() and 0xFF) + 1
            }
            pos += 5 // Null byte + QTYPE (2) + QCLASS (2)

            // Append Answer: Name pointer (0xc00c), Type A (1), Class IN (1), TTL (60), Length (4), IP 0.0.0.0
            val answer = byteArrayOf(
                0xc0.toByte(), 0x0c.toByte(), // Name pointer
                0x00, 0x01,                   // Type A
                0x00, 0x01,                   // Class IN
                0x00, 0x00, 0x00, 0x3c,       // TTL 60 seconds
                0x00, 0x04,                   // Data length 4
                0x00, 0x00, 0x00, 0x00        // IP 0.0.0.0 (Sinkhole)
            )

            val fullResponse = ByteArray(pos + answer.size)
            System.arraycopy(response, 0, fullResponse, 0, pos)
            System.arraycopy(answer, 0, fullResponse, pos, answer.size)

            val packet = DatagramPacket(
                fullResponse, fullResponse.size,
                InetAddress.getByName(clientIp), clientPort
            )
            socket?.send(packet)
        } catch (_: Exception) {}
    }

    /**
     * Forwards allowed DNS query to 8.8.8.8 and returns result to client.
     */
    private fun forwardToUpstream(clientIp: String, clientPort: Int, query: ByteArray) {
        try {
            val upstreamSocket = DatagramSocket()
            upstreamSocket.soTimeout = 3000
            val outPacket = DatagramPacket(query, query.size, UPSTREAM_DNS, UPSTREAM_PORT)
            upstreamSocket.send(outPacket)

            val responseBuf = ByteArray(1024)
            val inPacket = DatagramPacket(responseBuf, responseBuf.size)
            upstreamSocket.receive(inPacket)
            upstreamSocket.close()

            val clientPacket = DatagramPacket(
                inPacket.data, inPacket.length,
                InetAddress.getByName(clientIp), clientPort
            )
            socket?.send(clientPacket)
        } catch (_: Exception) {}
    }
}
