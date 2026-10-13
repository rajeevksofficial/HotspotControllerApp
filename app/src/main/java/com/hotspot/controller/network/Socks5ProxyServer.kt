package com.hotspot.controller.network

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standard RFC 1928 SOCKS5 Proxy Server on port 1080.
 * Allows non-root clients (laptops, companion apps, Telegram, Firefox, etc.)
 * to route full TCP traffic through D0 while enforcing speed limits and URL blocking.
 */
class Socks5ProxyServer(
    val port: Int = 1080,
    private val deviceManager: DeviceManager,
    private val urlRuleManager: UrlRuleManager,
    private val accessLogManager: AccessLogManager
) {
    companion object {
        private const val TAG = "Socks5ProxyServer"
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val isRunning = AtomicBoolean(false)

    data class ActiveSocksConnection(
        val clientSocket: Socket,
        var targetSocket: Socket? = null,
        val clientIp: String
    )

    private val activeConnections = ConcurrentHashMap.newKeySet<ActiveSocksConnection>()

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        executor.execute {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
                Log.i(TAG, "SOCKS5 Proxy Server started on 0.0.0.0:$port")

                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        executor.execute { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.e(TAG, "Error accepting SOCKS5 client: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SOCKS5 Server error on port $port: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for (conn in activeConnections) {
            try { conn.clientSocket.close() } catch (_: Exception) {}
            try { conn.targetSocket?.close() } catch (_: Exception) {}
        }
        activeConnections.clear()
        Log.i(TAG, "SOCKS5 Proxy Server stopped.")
    }

    fun terminateClientSockets(clientIp: String) {
        val matches = activeConnections.filter { it.clientIp == clientIp }
        for (conn in matches) {
            try { conn.clientSocket.close() } catch (_: Exception) {}
            try { conn.targetSocket?.close() } catch (_: Exception) {}
            activeConnections.remove(conn)
        }
    }

    fun isServerRunning(): Boolean = isRunning.get()

    private fun handleClient(clientSocket: Socket) {
        val clientIp = clientSocket.inetAddress?.hostAddress ?: "Unknown"
        deviceManager.markDeviceProxyRouted(clientIp, true)

        val connection = ActiveSocksConnection(clientSocket = clientSocket, clientIp = clientIp)
        activeConnections.add(connection)

        try {
            clientSocket.soTimeout = 12000
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // 1. SOCKS5 Handshake: Version and Auth methods
            val version = clientIn.read()
            if (version != 5) {
                clientSocket.close()
                return
            }
            val numMethods = clientIn.read()
            val methods = ByteArray(numMethods)
            clientIn.readFully(methods)

            // Respond: SOCKS5, No Auth Required (0x00)
            clientOut.write(byteArrayOf(0x05, 0x00))
            clientOut.flush()

            // Check if device is paused
            if (deviceManager.isDeviceBlocked(clientIp)) {
                // Connection not allowed by ruleset (0x02)
                clientOut.write(byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                clientSocket.close()
                return
            }

            // 2. SOCKS5 Connection Request
            val reqVer = clientIn.read()
            val cmd = clientIn.read() // 0x01 = CONNECT
            val rsv = clientIn.read() // Reserved 0x00
            val atyp = clientIn.read() // Address Type

            if (reqVer != 5 || cmd != 1) {
                // Command not supported (0x07)
                clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                clientSocket.close()
                return
            }

            var targetHost = ""
            when (atyp) {
                1 -> { // IPv4 (4 bytes)
                    val ipBytes = ByteArray(4)
                    clientIn.readFully(ipBytes)
                    targetHost = InetAddress.getByAddress(ipBytes).hostAddress ?: ""
                }
                3 -> { // Domain name (1 byte length + ASCII string)
                    val len = clientIn.read()
                    val hostBytes = ByteArray(len)
                    clientIn.readFully(hostBytes)
                    targetHost = String(hostBytes)
                }
                4 -> { // IPv6 (16 bytes)
                    val ipBytes = ByteArray(16)
                    clientIn.readFully(ipBytes)
                    targetHost = InetAddress.getByAddress(ipBytes).hostAddress ?: ""
                }
                else -> {
                    clientSocket.close()
                    return
                }
            }

            val p1 = clientIn.read()
            val p2 = clientIn.read()
            val targetPort = (p1 shl 8) or p2

            // Check URL / Domain Rules
            val dev = deviceManager.getOrCreateDevice(clientIp)
            val isDevPaused = deviceManager.isDeviceBlocked(clientIp)
            val (isRuleBlocked, ruleMatched) = urlRuleManager.isHostBlocked(targetHost)
            val isBlocked = isRuleBlocked || isDevPaused
            accessLogManager.log(
                clientIp = clientIp,
                targetHost = targetHost,
                protocol = "SOCKS5",
                isBlocked = isBlocked,
                isPaused = isDevPaused,
                ruleMatched = if (isDevPaused) "Device Paused" else ruleMatched,
                deviceName = deviceManager.getDeviceDisplayName(clientIp)
            )

            if (isBlocked || deviceManager.isDeviceBlocked(clientIp)) {
                clientOut.write(byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                clientSocket.close()
                return
            }

            // Connect to upstream target
            var targetSocket: Socket? = null
            try {
                targetSocket = Socket()
                targetSocket.connect(InetSocketAddress(targetHost, targetPort), 10000)
                connection.targetSocket = targetSocket

                // Success response: 0x05, 0x00, 0x00, 0x01, bind IP (0.0.0.0), port 0
                clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()

                val targetIn = targetSocket.getInputStream()
                val targetOut = targetSocket.getOutputStream()

                val downThrottler = deviceManager.getDeviceDownThrottler(clientIp)
                val upThrottler = deviceManager.getDeviceUpThrottler(clientIp)
                val globalThrottler = deviceManager.globalThrottler

                val uploadThread = Thread {
                    ThrottledStreams.pipe(
                        input = clientIn,
                        output = targetOut,
                        deviceThrottler = upThrottler,
                        globalThrottler = globalThrottler,
                        isBlockedProvider = { deviceManager.isDeviceBlocked(clientIp) },
                        onBytesTransferred = { bytes ->
                            deviceManager.recordTraffic(clientIp, downBytes = 0, upBytes = bytes)
                        }
                    )
                    try { targetSocket.close() } catch (_: Exception) {}
                }

                val downloadThread = Thread {
                    ThrottledStreams.pipe(
                        input = targetIn,
                        output = clientOut,
                        deviceThrottler = downThrottler,
                        globalThrottler = globalThrottler,
                        isBlockedProvider = { deviceManager.isDeviceBlocked(clientIp) },
                        onBytesTransferred = { bytes ->
                            deviceManager.recordTraffic(clientIp, downBytes = bytes, upBytes = 0)
                        }
                    )
                    try { clientSocket.close() } catch (_: Exception) {}
                }

                uploadThread.start()
                downloadThread.start()
                uploadThread.join()
                downloadThread.join()
            } finally {
                try { targetSocket?.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            activeConnections.remove(connection)
        }
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count < 0) break
            offset += count
        }
    }
}
