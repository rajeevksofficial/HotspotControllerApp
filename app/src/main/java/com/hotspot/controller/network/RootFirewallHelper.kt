package com.hotspot.controller.network

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

object RootFirewallHelper {
    private const val TAG = "RootFirewallHelper"

    @Volatile var isRootAvailable: Boolean? = null
        private set

    /**
     * Checks if the device has root access by actually probing the `su` shell.
     */
    fun isDeviceRooted(): Boolean {
        isRootAvailable?.let { return it }

        var rooted = false
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val line = reader.readLine()
                if (line != null && (line.contains("uid=0") || line.contains("root"))) {
                    rooted = true
                }
            }
            process.waitFor()
        } catch (_: Exception) {
            // Check filesystem paths as fallback
            val paths = arrayOf(
                "/sbin/su", "/system/bin/su", "/system/xbin/su",
                "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su"
            )
            rooted = paths.any { File(it).exists() }
        }

        isRootAvailable = rooted
        return rooted
    }

    /**
     * Executes commands in a root shell session.
     */
    fun executeSu(commands: List<String>): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { os ->
                for (cmd in commands) {
                    os.writeBytes("$cmd\n")
                }
                os.writeBytes("exit\n")
                os.flush()
            }
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root execution failed: ${e.message}")
            false
        }
    }

    /**
     * Enables transparent iptables forwarding from hotspot clients to local proxy & DNS ports.
     * When active, clients D1-D10 do NOT need manual proxy configuration.
     */
    fun enableTransparentProxy(port: Int = 8080, dnsPort: Int = 5353): Boolean {
        if (!isDeviceRooted()) return false
        val cmds = listOf(
            "echo 1 > /proc/sys/net/ipv4/ip_forward",
            "iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports $port",
            "iptables -t nat -A PREROUTING -p tcp --dport 443 -j REDIRECT --to-ports $port",
            "iptables -t nat -A PREROUTING -p udp --dport 53 -j REDIRECT --to-ports $dnsPort"
        )
        return executeSu(cmds)
    }

    /**
     * Removes transparent proxy iptables rules.
     */
    fun disableTransparentProxy(port: Int = 8080, dnsPort: Int = 5353): Boolean {
        if (!isDeviceRooted()) return false
        val cmds = listOf(
            "iptables -t nat -D PREROUTING -p tcp --dport 80 -j REDIRECT --to-ports $port 2>/dev/null",
            "iptables -t nat -D PREROUTING -p tcp --dport 443 -j REDIRECT --to-ports $port 2>/dev/null",
            "iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports $dnsPort 2>/dev/null"
        )
        return executeSu(cmds)
    }

    /**
     * Drops all packets for a client IP at kernel firewall level (instant hardware kill switch).
     */
    fun setClientFirewallBlocked(clientIp: String, isBlocked: Boolean): Boolean {
        if (!isDeviceRooted()) return false
        val cmds = if (isBlocked) {
            listOf(
                "iptables -I FORWARD -s $clientIp -j DROP",
                "iptables -I FORWARD -d $clientIp -j DROP",
                "iptables -I INPUT -s $clientIp -j DROP",
                "iptables -I OUTPUT -d $clientIp -j DROP",
                "iptables -t nat -I PREROUTING -s $clientIp -p udp --dport 53 -j DROP"
            )
        } else {
            listOf(
                "iptables -D FORWARD -s $clientIp -j DROP 2>/dev/null",
                "iptables -D FORWARD -d $clientIp -j DROP 2>/dev/null",
                "iptables -D INPUT -s $clientIp -j DROP 2>/dev/null",
                "iptables -D OUTPUT -d $clientIp -j DROP 2>/dev/null",
                "iptables -t nat -D PREROUTING -s $clientIp -p udp --dport 53 -j DROP 2>/dev/null"
            )
        }
        return executeSu(cmds)
    }
}
