package com.hotspot.controller.network

import java.io.BufferedInputStream

/**
 * Extracts exact hostname / URL from raw initial TCP payload bytes without modifying the stream:
 *  - Parses TLS 1.0/1.1/1.2/1.3 ClientHello packets to extract the Server Name Indication (SNI) extension (0x0000).
 *  - Parses plain HTTP request headers (GET, POST, etc.) to extract the full URL (http://host/path).
 */
object TlsSniExtractor {

    data class ExtractedRequest(
        val fullUrl: String,
        val host: String,
        val protocol: String
    )

    /**
     * Peeks at the beginning of a [BufferedInputStream] (up to 4096 bytes) and resets the stream position
     * so downstream consumers receive 100% of the original bytes untouched.
     */
    fun peekRequestUrl(input: BufferedInputStream, defaultPort: Int = 443): ExtractedRequest? {
        try {
            input.mark(4096)
            val buffer = ByteArray(2048)
            val bytesRead = input.read(buffer, 0, buffer.size)
            input.reset()

            if (bytesRead <= 0) return null

            // Check if TLS Handshake (0x16 = Handshake, 0x03 = SSLv3 / TLS 1.x)
            if ((buffer[0].toInt() and 0xFF) == 0x16 && bytesRead > 43) {
                val sniHost = parseTlsClientHelloSni(buffer, bytesRead)
                if (!sniHost.isNullOrBlank()) {
                    return ExtractedRequest(
                        fullUrl = "https://$sniHost/",
                        host = sniHost,
                        protocol = "HTTPS"
                    )
                }
            }

            // Check if plain HTTP request
            val preview = String(buffer, 0, bytesRead.coerceAtMost(1024), Charsets.ISO_8859_1)
            val lines = preview.split("\r\n", "\n")
            if (lines.isNotEmpty()) {
                val firstLine = lines[0].trim()
                val parts = firstLine.split(" ")
                if (parts.size >= 2) {
                    val method = parts[0].uppercase()
                    if (method in listOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH")) {
                        val rawUri = parts[1]
                        var host = ""
                        for (i in 1 until lines.size) {
                            val line = lines[i]
                            if (line.isBlank()) break
                            if (line.startsWith("Host:", ignoreCase = true)) {
                                host = line.substring(5).trim()
                                break
                            }
                        }
                        if (rawUri.startsWith("http://", ignoreCase = true) || rawUri.startsWith("https://", ignoreCase = true)) {
                            val extractedHost = rawUri.substringAfter("://").substringBefore("/").substringBefore(":")
                            return ExtractedRequest(
                                fullUrl = rawUri,
                                host = host.ifEmpty { extractedHost },
                                protocol = if (rawUri.startsWith("https", ignoreCase = true)) "HTTPS" else "HTTP"
                            )
                        } else if (host.isNotEmpty()) {
                            val scheme = if (defaultPort == 443) "https" else "http"
                            val path = if (rawUri.startsWith("/")) rawUri else "/$rawUri"
                            return ExtractedRequest(
                                fullUrl = "$scheme://$host$path",
                                host = host.substringBefore(":"),
                                protocol = scheme.uppercase()
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            try { input.reset() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Parses a raw TLS ClientHello record to extract the SNI (Server Name Indication) domain string.
     */
    fun parseTlsClientHelloSni(data: ByteArray, length: Int): String? {
        try {
            if (length < 43) return null
            // Record Header: ContentType(1) = 0x16, Version(2), Length(2)
            if ((data[0].toInt() and 0xFF) != 0x16) return null

            // Handshake Header starts at offset 5: HandshakeType(1) = 0x01 (ClientHello)
            if ((data[5].toInt() and 0xFF) != 0x01) return null

            // Skip HandshakeType(1) + HandshakeLength(3) + ClientVersion(2) + Random(32) = 38 bytes -> offset 43
            var pos = 43
            if (pos >= length) return null

            // Session ID
            val sessionIdLen = data[pos].toInt() and 0xFF
            pos += 1 + sessionIdLen
            if (pos + 2 > length) return null

            // Cipher Suites
            val cipherSuitesLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2 + cipherSuitesLen
            if (pos + 1 > length) return null

            // Compression Methods
            val compressionMethodsLen = data[pos].toInt() and 0xFF
            pos += 1 + compressionMethodsLen
            if (pos + 2 > length) return null

            // Extensions Length
            val extensionsLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            val extensionsEnd = (pos + extensionsLen).coerceAtMost(length)

            while (pos + 4 <= extensionsEnd) {
                val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val extLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                pos += 4

                if (pos + extLen > extensionsEnd) break

                // Extension 0x0000 = server_name (SNI)
                if (extType == 0x0000 && extLen >= 5) {
                    val serverNameListLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    var listPos = pos + 2
                    val listEnd = (listPos + serverNameListLen).coerceAtMost(pos + extLen)

                    while (listPos + 3 <= listEnd) {
                        val nameType = data[listPos].toInt() and 0xFF
                        val nameLen = ((data[listPos + 1].toInt() and 0xFF) shl 8) or (data[listPos + 2].toInt() and 0xFF)
                        listPos += 3
                        if (listPos + nameLen > listEnd) break
                        if (nameType == 0) { // host_name (0)
                            val host = String(data, listPos, nameLen, Charsets.US_ASCII).trim().lowercase()
                            if (host.isNotEmpty() && host.contains(".")) {
                                return host
                            }
                        }
                        listPos += nameLen
                    }
                }
                pos += extLen
            }
        } catch (_: Exception) {}
        return null
    }
}
