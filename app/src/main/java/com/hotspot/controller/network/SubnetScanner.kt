package com.hotspot.controller.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredHost(
    val ip: String,
    val hostname: String? = null,
    val responseTimeMs: Long = 0L
)

object SubnetScanner {
    private const val TAG = "SubnetScanner"
    private val isScanning = AtomicBoolean(false)
    private val threadPool = Executors.newFixedThreadPool(25)

    fun isScanInProgress(): Boolean = isScanning.get()

    /**
     * Performs a concurrent active sweep across the hotspot subnet (e.g. 192.168.43.2 .. 192.168.43.254).
     * Invokes [onDeviceFound] immediately whenever a responsive client is discovered.
     */
    fun scanSubnet(
        hostIp: String,
        onDeviceFound: (DiscoveredHost) -> Unit,
        onScanComplete: () -> Unit
    ) {
        if (isScanning.getAndSet(true)) {
            Log.d(TAG, "Scan already in progress.")
            return
        }

        Thread {
            try {
                val subnetPrefix = if (hostIp.contains(".")) {
                    hostIp.substringBeforeLast(".") + "."
                } else {
                    "192.168.43."
                }

                val lastOctet = hostIp.substringAfterLast(".").toIntOrNull() ?: 1
                val foundHosts = mutableListOf<DiscoveredHost>()
                val tasks = mutableListOf<java.util.concurrent.Future<*>>()

                // Scan common hotspot client address range (typically DHCP assigns .2 to .100)
                // We scan .2 to .254
                for (i in 2..254) {
                    if (i == lastOctet) continue
                    val targetIp = "$subnetPrefix$i"

                    val future = threadPool.submit {
                        val host = probeHost(targetIp)
                        if (host != null) {
                            synchronized(foundHosts) {
                                foundHosts.add(host)
                            }
                            onDeviceFound(host)
                        }
                    }
                    tasks.add(future)
                }

                // Wait for all probes to finish
                for (f in tasks) {
                    try {
                        f.get()
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during subnet scan: ${e.message}")
            } finally {
                isScanning.set(false)
                onScanComplete()
            }
        }.start()
    }

    /**
     * Probes an individual IP address using multiple complementary techniques:
     * 1. ICMP echo (isReachable)
     * 2. TCP socket probes to common mobile services (HTTP 80, HTTPS 443, DNS 53, mDNS 5353, NetBIOS 137, Android ADB 5555)
     * 3. UDP probe packet to prompt kernel ARP cache update
     */
    private fun probeHost(ip: String): DiscoveredHost? {
        val startTime = System.currentTimeMillis()
        var reachable = false
        var address: InetAddress? = null

        try {
            address = InetAddress.getByName(ip)
            // 1. Try isReachable
            reachable = address.isReachable(200)
        } catch (_: Exception) {}

        // 2. Try TCP socket connect if ICMP ping is blocked by mobile firewall
        if (!reachable) {
            val commonPorts = intArrayOf(80, 443, 53, 5353, 137, 8080, 5555)
            for (port in commonPorts) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ip, port), 120)
                    socket.close()
                    reachable = true
                    break
                } catch (_: Exception) {
                    // Port closed or host unreachable
                }
            }
        }

        // 3. Send a lightweight UDP probe to wake device & trigger ARP resolution in Linux kernel
        try {
            val dSocket = DatagramSocket()
            dSocket.soTimeout = 80
            val dummy = byteArrayOf(0x00, 0x01)
            val packet = DatagramPacket(dummy, dummy.size, address ?: InetAddress.getByName(ip), 137)
            dSocket.send(packet)
            dSocket.close()
        } catch (_: Exception) {}

        if (reachable && address != null) {
            val elapsed = System.currentTimeMillis() - startTime
            val hostname = resolveHostname(address, ip)
            return DiscoveredHost(ip = ip, hostname = hostname, responseTimeMs = elapsed)
        }

        return null
    }

    private fun resolveHostname(address: InetAddress, ip: String): String? {
        return try {
            val canonical = address.canonicalHostName
            if (canonical != ip && canonical.isNotBlank()) {
                canonical
            } else {
                val hostName = address.hostName
                if (hostName != ip && hostName.isNotBlank()) hostName else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
