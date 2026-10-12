package com.hotspot.controller.network

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpProxyServer(
    val port: Int = 8080,
    private val deviceManager: DeviceManager,
    private val urlRuleManager: UrlRuleManager,
    private val accessLogManager: AccessLogManager
) {
    companion object {
        private const val TAG = "HttpProxyServer"
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val isRunning = AtomicBoolean(false)

    data class ActiveHttpConnection(
        val clientSocket: Socket,
        var targetSocket: Socket? = null,
        val clientIp: String
    )

    // Active connection pairs to allow instant, clean socket killswitch termination
    private val activeConnections = ConcurrentHashMap.newKeySet<ActiveHttpConnection>()

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        executor.execute {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
                Log.i(TAG, "Hotspot HTTP Proxy Server started on 0.0.0.0:$port")

                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        executor.execute { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.e(TAG, "Error accepting client: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error on port $port: ${e.message}")
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

        // Close all active client connections
        for (conn in activeConnections) {
            try { conn.clientSocket.close() } catch (_: Exception) {}
            try { conn.targetSocket?.close() } catch (_: Exception) {}
        }
        activeConnections.clear()
        Log.i(TAG, "Hotspot Proxy Server stopped.")
    }

    /**
     * Instantly kills and severs all active connections (both client and upstream) for [clientIp].
     */
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

        // Mark client as actively routed through controller proxy
        deviceManager.markDeviceProxyRouted(clientIp, true)

        val connection = ActiveHttpConnection(clientSocket = clientSocket, clientIp = clientIp)
        activeConnections.add(connection)

        try {
            clientSocket.soTimeout = 12000
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // Read the initial HTTP request line
            val reader = BufferedReader(InputStreamReader(clientIn))
            val initialLine = reader.readLine() ?: run {
                clientSocket.close()
                return
            }

            val parts = initialLine.split(" ")
            if (parts.size < 2) {
                clientSocket.close()
                return
            }

            val method = parts[0].uppercase()
            val uri = parts[1]

            // Check if device is paused
            if (deviceManager.isDeviceBlocked(clientIp)) {
                val dev = deviceManager.getOrCreateDevice(clientIp)
                accessLogManager.log(
                    clientIp = clientIp,
                    targetHost = uri,
                    protocol = if (method == "CONNECT") "HTTPS" else "HTTP",
                    isBlocked = true,
                    isPaused = true,
                    ruleMatched = "Device Paused",
                    deviceName = dev.getDisplayName(0)
                )
                if (method == "CONNECT") {
                    val response = "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nInternet access has been paused for this device by D0 hotspot administrator.\r\n"
                    clientOut.write(response.toByteArray())
                    clientOut.flush()
                } else {
                    sendPausedHtmlPage(clientOut, clientIp)
                }
                clientSocket.close()
                return
            }

            // Check if device is allowed under max device limit (e.g. 10 devices)
            if (!deviceManager.canAcceptClient(clientIp)) {
                val response = "HTTP/1.1 429 Too Many Connections\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nHotspot client limit of ${deviceManager.maxDeviceLimit} reached.\r\n"
                clientOut.write(response.toByteArray())
                clientOut.flush()
                clientSocket.close()
                return
            }

            if (method == "CONNECT") {
                handleHttpsConnect(connection, clientSocket, clientIn, clientOut, clientIp, uri, reader)
            } else {
                handleHttp(connection, clientSocket, clientIn, clientOut, clientIp, method, uri, reader)
            }
        } catch (e: Exception) {
            try { clientSocket.close() } catch (_: Exception) {}
        } finally {
            activeConnections.remove(connection)
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Handles HTTPS CONNECT tunneling.
     */
    private fun handleHttpsConnect(
        connection: ActiveHttpConnection,
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        clientIp: String,
        target: String,
        reader: BufferedReader
    ) {
        // Read remaining CONNECT headers (e.g. User-Agent) up to blank line
        var hLine: String?
        while (reader.readLine().also { hLine = it } != null) {
            if (hLine.isNullOrBlank()) break
            if (hLine!!.startsWith("User-Agent:", ignoreCase = true)) {
                val ua = hLine!!.substring(11).trim()
                val detected = DeviceNameResolver.parseUserAgent(ua)
                if (detected != null) {
                    deviceManager.setResolvedDeviceInfo(clientIp, detected)
                }
            }
        }

        val hostParts = target.split(":")
        val host = hostParts[0]
        val targetPort = if (hostParts.size > 1) hostParts[1].toIntOrNull() ?: 443 else 443

        // Infer OS from host
        val inferredOs = DeviceNameResolver.inferOsFromDomain(host)
        if (inferredOs != null) {
            deviceManager.updateDeviceOs(clientIp, inferredOs)
        }

        // Check if URL / Domain is blocked
        val dev = deviceManager.getOrCreateDevice(clientIp)
        val isDevPaused = deviceManager.isDeviceBlocked(clientIp)
        val (isRuleBlocked, ruleMatched) = urlRuleManager.isHostBlocked(host)
        val isBlocked = isRuleBlocked || isDevPaused
        accessLogManager.log(
            clientIp = clientIp,
            targetHost = host,
            protocol = "HTTPS",
            isBlocked = isBlocked,
            isPaused = isDevPaused,
            ruleMatched = if (isDevPaused) "Device Paused" else ruleMatched,
            deviceName = dev.getDisplayName(0)
        )

        if (isBlocked || deviceManager.isDeviceBlocked(clientIp)) {
            try {
                val blockResponse = "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nBlocked by Hotspot Controller: $host\r\n"
                clientOut.write(blockResponse.toByteArray())
                clientOut.flush()
            } finally {
                clientSocket.close()
            }
            return
        }

        // Establish connection to upstream target
        var targetSocket: Socket? = null
        try {
            targetSocket = Socket()
            targetSocket.connect(InetSocketAddress(host, targetPort), 10000)
            connection.targetSocket = targetSocket

            // Send 200 OK to client to establish SSL tunnel
            val okResponse = "HTTP/1.1 200 Connection Established\r\n\r\n"
            clientOut.write(okResponse.toByteArray())
            clientOut.flush()

            val targetIn = targetSocket.getInputStream()
            val targetOut = targetSocket.getOutputStream()

            val downThrottler = deviceManager.getDeviceDownThrottler(clientIp)
            val upThrottler = deviceManager.getDeviceUpThrottler(clientIp)
            val globalThrottler = deviceManager.globalThrottler

            // Upstream: Client -> Target (Upload)
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

            // Downstream: Target -> Client (Download)
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
        } catch (e: Exception) {
            Log.d(TAG, "HTTPS tunnel error: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { targetSocket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Handles standard HTTP requests (GET, POST, etc.).
     */
    private fun handleHttp(
        connection: ActiveHttpConnection,
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        clientIp: String,
        method: String,
        uri: String,
        reader: BufferedReader
    ) {
        var host = ""
        var targetPort = 80
        var path = uri

        if (uri.startsWith("http://", ignoreCase = true)) {
            val withoutProtocol = uri.substring(7)
            val slashIdx = withoutProtocol.indexOf('/')
            val hostAndPort = if (slashIdx != -1) withoutProtocol.substring(0, slashIdx) else withoutProtocol
            path = if (slashIdx != -1) withoutProtocol.substring(slashIdx) else "/"

            if (hostAndPort.contains(":")) {
                host = hostAndPort.substringBefore(":")
                targetPort = hostAndPort.substringAfter(":").toIntOrNull() ?: 80
            } else {
                host = hostAndPort
            }
        }

        // Read remaining headers and inspect User-Agent for device identification
        val headers = mutableListOf<String>()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (line.isNullOrBlank()) break
            headers.add(line!!)

            if (host.isEmpty() && line!!.startsWith("Host:", ignoreCase = true)) {
                val hostHeaderVal = line!!.substring(5).trim()
                if (hostHeaderVal.contains(":")) {
                    host = hostHeaderVal.substringBefore(":")
                    targetPort = hostHeaderVal.substringAfter(":").toIntOrNull() ?: 80
                } else {
                    host = hostHeaderVal
                }
            }

            if (line!!.startsWith("User-Agent:", ignoreCase = true)) {
                val ua = line!!.substring(11).trim()
                val detected = DeviceNameResolver.parseUserAgent(ua)
                if (detected != null) {
                    deviceManager.setResolvedDeviceInfo(clientIp, detected)
                }
            }
        }

        val hotspotIp = HotspotNetworkHelper.getHotspotOrLocalIp()

        // Handle internal requests to proxy server itself
        if (host == "localhost" || host == "127.0.0.1" || host == hotspotIp || host.isEmpty() || uri == "/proxy.pac" || uri == "/status" || uri == "/") {
            handleInternalPortal(clientOut, clientIp, uri, hotspotIp)
            try { clientSocket.close() } catch (_: Exception) {}
            return
        }

        // Infer OS from host
        val inferredOs = DeviceNameResolver.inferOsFromDomain(host)
        if (inferredOs != null) {
            deviceManager.updateDeviceOs(clientIp, inferredOs)
        }

        // Check if URL/domain is blocked or device is blocked
        val dev = deviceManager.getOrCreateDevice(clientIp)
        val isDevPaused = deviceManager.isDeviceBlocked(clientIp)
        val (isRuleBlocked, ruleMatched) = urlRuleManager.isHostBlocked(host)
        val isBlocked = isRuleBlocked || isDevPaused
        accessLogManager.log(
            clientIp = clientIp,
            targetHost = host,
            protocol = "HTTP",
            isBlocked = isBlocked,
            isPaused = isDevPaused,
            ruleMatched = if (isDevPaused) "Device Paused" else ruleMatched,
            deviceName = dev.getDisplayName(0)
        )

        if (isBlocked || deviceManager.isDeviceBlocked(clientIp)) {
            sendPausedHtmlPage(clientOut, clientIp, host)
            try { clientSocket.close() } catch (_: Exception) {}
            return
        }

        // Connect to upstream target
        var targetSocket: Socket? = null
        try {
            targetSocket = Socket()
            targetSocket.connect(InetSocketAddress(host, targetPort), 10000)
            connection.targetSocket = targetSocket

            val targetOut = targetSocket.getOutputStream()
            val targetIn = targetSocket.getInputStream()

            val requestLine = "$method $path HTTP/1.1\r\n"
            targetOut.write(requestLine.toByteArray())
            for (h in headers) {
                targetOut.write("$h\r\n".toByteArray())
            }
            targetOut.write("\r\n".toByteArray())
            targetOut.flush()

            val downThrottler = deviceManager.getDeviceDownThrottler(clientIp)
            val globalThrottler = deviceManager.globalThrottler

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
        } catch (e: Exception) {
            Log.d(TAG, "HTTP error: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { targetSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun sendPausedHtmlPage(out: OutputStream, clientIp: String, host: String = "") {
        try {
            val isDevBlocked = deviceManager.isDeviceBlocked(clientIp)
            val title = if (isDevBlocked) "Internet Paused" else "Website Blocked"
            val headline = if (isDevBlocked) "⏸️ Internet Access Paused" else "🚫 Access Blocked"
            val desc = if (isDevBlocked) {
                "Internet access for this device ($clientIp) has been paused by the D0 Hotspot Administrator."
            } else {
                "Access to website <b>$host</b> has been restricted by the D0 Hotspot Administrator."
            }

            val htmlBody = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>$title - Hotspot Controller</title>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        body { font-family: -apple-system, Roboto, sans-serif; background: #0f172a; color: #f8fafc; text-align: center; padding: 40px 20px; }
                        .card { background: #1e293b; border-radius: 16px; padding: 30px; max-width: 460px; margin: 0 auto; box-shadow: 0 8px 30px rgba(0,0,0,0.5); border: 1px solid #ef4444; }
                        h1 { color: #ef4444; font-size: 22px; margin-bottom: 12px; }
                        p { color: #94a3b8; font-size: 14px; line-height: 1.5; }
                        .badge { background: #fee2e2; color: #991b1b; padding: 5px 12px; border-radius: 20px; font-weight: bold; font-size: 12px; display: inline-block; margin-bottom: 14px; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <h1>$headline</h1>
                        <span class="badge">POLICY ENFORCED</span>
                        <p>$desc</p>
                    </div>
                </body>
                </html>
            """.trimIndent()

            val response = "HTTP/1.1 403 Forbidden\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: ${htmlBody.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    htmlBody

            out.write(response.toByteArray())
            out.flush()
        } catch (_: Exception) {}
    }

    private fun handleInternalPortal(out: OutputStream, clientIp: String, uri: String, hostIp: String) {
        if (uri.contains("proxy.pac")) {
            val pacScript = """
                function FindProxyForURL(url, host) {
                    if (shExpMatch(host, "192.168.*") || shExpMatch(host, "10.*") || shExpMatch(host, "127.*")) {
                        return "DIRECT";
                    }
                    return "PROXY $hostIp:$port; DIRECT";
                }
            """.trimIndent()
            val resp = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/x-ns-proxy-autoconfig\r\n" +
                    "Content-Length: ${pacScript.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    pacScript
            out.write(resp.toByteArray())
            out.flush()
            return
        }

        val dev = deviceManager.getOrCreateDevice(clientIp)
        val speedLabel = if (dev.speedLimitKbps <= 0) "Unlimited" else "${dev.speedLimitKbps} Kbps"
        val statusHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Hotspot Gateway Active</title>
                <style>
                    body { font-family: -apple-system, Roboto, sans-serif; background: #0f172a; color: #f8fafc; text-align: center; padding: 40px 16px; }
                    .card { background: #1e293b; border-radius: 16px; padding: 24px; max-width: 440px; margin: 0 auto; border: 1px solid #3b82f6; box-shadow: 0 10px 25px rgba(0,0,0,0.5); }
                    h1 { color: #3b82f6; font-size: 22px; margin-bottom: 6px; }
                    .badge { background: #065f46; color: #34d399; padding: 4px 12px; border-radius: 20px; font-weight: bold; font-size: 13px; display: inline-block; margin-bottom: 16px; }
                    .row { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid #334155; font-size: 14px; text-align: left; }
                    .label { color: #94a3b8; }
                    .val { font-weight: bold; color: #f8fafc; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>📡 Hotspot Controller (D0)</h1>
                    <div class="badge">● CONNECTED &amp; CONTROLLED</div>
                    <div class="row"><span class="label">Your Device IP:</span><span class="val">$clientIp</span></div>
                    <div class="row"><span class="label">Host (D0) IP:</span><span class="val">$hostIp</span></div>
                    <div class="row"><span class="label">Your Speed Limit:</span><span class="val">$speedLabel</span></div>
                    <div class="row"><span class="label">Device Name:</span><span class="val">${dev.getDisplayName(0)}</span></div>
                    <div class="row"><span class="label">Device Type:</span><span class="val">${dev.getCategoryLabel()}</span></div>
                    <div class="row"><span class="label">Status:</span><span class="val">${if (dev.isBlocked) "Paused" else "Active"}</span></div>
                    <p style="color:#94a3b8; font-size:12px; margin-top:20px;">Your internet traffic is safely routed and managed through Host D0.</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val resp = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: ${statusHtml.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                statusHtml
        out.write(resp.toByteArray())
        out.flush()
    }
}
