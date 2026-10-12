package com.hotspot.controller.network

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hotspot.controller.HotspotControllerApp
import com.hotspot.controller.model.ConnectedDevice
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class DeviceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("devices_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val livenessExecutor = Executors.newFixedThreadPool(4)

    // Keyed by Client IP
    private val devicesMap = ConcurrentHashMap<String, ConnectedDevice>()
    private val downThrottlersMap = ConcurrentHashMap<String, BandwidthThrottler>()
    private val upThrottlersMap = ConcurrentHashMap<String, BandwidthThrottler>()

    // Global speed limit (Kbps, 0 = unlimited)
    @Volatile var globalSpeedLimitKbps: Int = 0
        set(value) {
            field = value
            globalThrottler.rateLimitKbps = value
            prefs.edit().putInt("global_speed_limit", value).apply()
        }

    val globalThrottler = BandwidthThrottler(0)

    // Device limit (default 10 devices, configurable)
    @Volatile var maxDeviceLimit: Int = 10
        set(value) {
            field = value
            prefs.edit().putInt("max_device_limit", value).apply()
        }

    private val listeners = mutableListOf<() -> Unit>()

    init {
        globalSpeedLimitKbps = prefs.getInt("global_speed_limit", 0)
        maxDeviceLimit = prefs.getInt("max_device_limit", 10)
        loadSavedDevices()
    }

    private fun loadSavedDevices() {
        val json = prefs.getString("saved_devices_json", null)
        if (json != null) {
            try {
                val type = object : TypeToken<Map<String, SavedDeviceInfo>>() {}.type
                val saved: Map<String, SavedDeviceInfo> = gson.fromJson(json, type)
                for ((ip, info) in saved) {
                    val validMac = if (ArpScanner.isValidMac(info.macAddress)) info.macAddress else ""
                    val cleanResolvedName = if (info.resolvedDeviceName.startsWith("Mobile", ignoreCase = true) || info.resolvedDeviceName.startsWith("Device", ignoreCase = true)) "" else info.resolvedDeviceName
                    val os = if (info.osInfo.isNotBlank()) info.osInfo else "Android"
                    val device = ConnectedDevice(
                        ipAddress = ip,
                        macAddress = validMac,
                        customName = info.customName,
                        resolvedDeviceName = cleanResolvedName,
                        deviceCategory = if (info.deviceCategory.isNotBlank()) info.deviceCategory else "Mobile",
                        osInfo = os,
                        isBlocked = info.isBlocked,
                        speedLimitKbps = info.speedLimitKbps,
                        firstConnectedTimestamp = if (info.firstConnectedTimestamp > 0L) info.firstConnectedTimestamp else System.currentTimeMillis(),
                        isOnline = false, // Set offline until verified active
                        isProxyRouted = false
                    )
                    devicesMap[ip] = device
                    downThrottlersMap[ip] = BandwidthThrottler(device.speedLimitKbps)
                    upThrottlersMap[ip] = BandwidthThrottler(device.speedLimitKbps)
                }
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    private fun saveDevices() {
        val saveMap = mutableMapOf<String, SavedDeviceInfo>()
        for ((ip, device) in devicesMap) {
            saveMap[ip] = SavedDeviceInfo(
                macAddress = device.macAddress,
                customName = device.customName,
                resolvedDeviceName = device.resolvedDeviceName,
                deviceCategory = device.deviceCategory,
                osInfo = device.osInfo,
                isBlocked = device.isBlocked,
                speedLimitKbps = device.speedLimitKbps,
                firstConnectedTimestamp = device.firstConnectedTimestamp
            )
        }
        val json = gson.toJson(saveMap)
        prefs.edit().putString("saved_devices_json", json).apply()
    }

    /**
     * Checks if a connection from [clientIp] is allowed under the device limit.
     */
    fun canAcceptClient(clientIp: String): Boolean {
        if (devicesMap.containsKey(clientIp)) {
            val dev = devicesMap[clientIp]
            return !(dev?.isBlocked ?: false)
        }
        return getActiveDeviceCount() < maxDeviceLimit
    }

    /**
     * Registers or updates a client device on connection.
     */
    fun getOrCreateDevice(clientIp: String): ConnectedDevice {
        return devicesMap.computeIfAbsent(clientIp) { ip ->
            val dev = ConnectedDevice(
                ipAddress = ip,
                isOnline = true,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            downThrottlersMap[ip] = BandwidthThrottler(dev.speedLimitKbps)
            upThrottlersMap[ip] = BandwidthThrottler(dev.speedLimitKbps)
            // Resolve device model/name in background
            DeviceNameResolver.resolveDeviceNameAsync(ip, null) { result ->
                setResolvedDeviceInfo(ip, result)
            }
            saveDevices()
            notifyListeners()
            dev
        }
    }

    /**
     * Marks whether a client device is actively routing through the controller gateway.
     */
    fun markDeviceProxyRouted(clientIp: String, isRouted: Boolean) {
        if (clientIp.isBlank() || clientIp == "127.0.0.1" || clientIp == "0.0.0.0") return
        val dev = getOrCreateDevice(clientIp)
        dev.lastSeenTimestamp = System.currentTimeMillis()
        dev.isOnline = true
        if (dev.isProxyRouted != isRouted) {
            dev.isProxyRouted = isRouted
            notifyListeners()
        }
    }

    /**
     * Registers a host discovered via Subnet sweep, DNS query, or ARP scan.
     */
    fun registerDiscoveredHost(ip: String, hostname: String? = null, mac: String? = null) {
        if (ip.isBlank() || ip == "127.0.0.1" || ip == "0.0.0.0" || ip == "Unknown") return
        var changed = false
        val dev = devicesMap[ip]
        val validMac = if (ArpScanner.isValidMac(mac)) mac!! else ""

        if (dev != null) {
            dev.isOnline = true
            dev.lastSeenTimestamp = System.currentTimeMillis()
            if (validMac.isNotBlank() && dev.macAddress != validMac) {
                dev.macAddress = validMac
                changed = true
                val vendor = DeviceNameResolver.lookupMacVendor(validMac)
                if (vendor != null && dev.manufacturerVendor.isBlank()) {
                    dev.manufacturerVendor = vendor
                    changed = true
                }
            }
            if (!hostname.isNullOrBlank() && !hostname.startsWith("Mobile", ignoreCase = true) && !hostname.startsWith("Device", ignoreCase = true) && dev.resolvedDeviceName.isBlank()) {
                dev.resolvedDeviceName = hostname
                changed = true
            }
        } else if (getActiveDeviceCount() < maxDeviceLimit) {
            val vendor = if (validMac.isNotBlank()) DeviceNameResolver.lookupMacVendor(validMac) ?: "" else ""
            val cleanHost = if (hostname != null && !hostname.startsWith("Mobile", ignoreCase = true) && !hostname.startsWith("Device", ignoreCase = true)) hostname else ""
            val newDev = ConnectedDevice(
                ipAddress = ip,
                macAddress = validMac,
                resolvedDeviceName = cleanHost,
                manufacturerVendor = vendor,
                osInfo = "Android",
                isOnline = true,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            devicesMap[ip] = newDev
            downThrottlersMap[ip] = BandwidthThrottler(0)
            upThrottlersMap[ip] = BandwidthThrottler(0)
            changed = true

            // Trigger background resolution
            DeviceNameResolver.resolveDeviceNameAsync(ip, if (validMac.isNotBlank()) validMac else null) { result ->
                setResolvedDeviceInfo(ip, result)
            }
        }

        if (changed) {
            saveDevices()
            notifyListeners()
        }
    }

    fun updateDeviceOs(clientIp: String, os: String) {
        if (clientIp.isBlank() || os.isBlank()) return
        val dev = devicesMap[clientIp] ?: return
        if (dev.osInfo != os) {
            dev.osInfo = os
            saveDevices()
            notifyListeners()
        }
    }

    fun setResolvedDeviceInfo(clientIp: String, result: DeviceResolutionResult) {
        val dev = devicesMap[clientIp] ?: return
        var changed = false
        if (result.deviceName.isNotBlank() && !result.deviceName.startsWith("Device", ignoreCase = true) && !result.deviceName.startsWith("Mobile", ignoreCase = true)) {
            dev.resolvedDeviceName = result.deviceName
            changed = true
        }
        if (result.deviceCategory.isNotBlank() && dev.deviceCategory != result.deviceCategory) {
            dev.deviceCategory = result.deviceCategory
            changed = true
        }
        if (result.osInfo.isNotBlank() && dev.osInfo != result.osInfo) {
            dev.osInfo = result.osInfo
            changed = true
        }
        if (changed) {
            saveDevices()
            notifyListeners()
        }
    }

    fun setResolvedDeviceName(clientIp: String, name: String) {
        val dev = devicesMap[clientIp] ?: return
        if (dev.resolvedDeviceName != name) {
            dev.resolvedDeviceName = name
            saveDevices()
            notifyListeners()
        }
    }

    fun getDeviceDownThrottler(clientIp: String): BandwidthThrottler {
        return downThrottlersMap.computeIfAbsent(clientIp) {
            val dev = devicesMap[clientIp]
            BandwidthThrottler(dev?.speedLimitKbps ?: 0)
        }
    }

    fun getDeviceUpThrottler(clientIp: String): BandwidthThrottler {
        return upThrottlersMap.computeIfAbsent(clientIp) {
            val dev = devicesMap[clientIp]
            BandwidthThrottler(dev?.speedLimitKbps ?: 0)
        }
    }

    fun getDeviceThrottler(clientIp: String): BandwidthThrottler {
        return getDeviceDownThrottler(clientIp)
    }

    fun isDeviceBlocked(clientIp: String): Boolean {
        return devicesMap[clientIp]?.isBlocked == true
    }

    /**
     * Set internet speed limit for a specific device in Kbps (e.g. 256, 512, 1024, 2048, 0 for unlimited).
     */
    fun setDeviceSpeedLimit(clientIp: String, speedLimitKbps: Int) {
        val dev = devicesMap[clientIp] ?: return
        dev.speedLimitKbps = speedLimitKbps
        getDeviceDownThrottler(clientIp).rateLimitKbps = speedLimitKbps
        getDeviceUpThrottler(clientIp).rateLimitKbps = speedLimitKbps
        saveDevices()
        notifyListeners()
    }

    /**
     * Completely block or unblock internet for a specific device.
     */
    fun setDeviceBlocked(clientIp: String, blocked: Boolean) {
        val dev = devicesMap[clientIp] ?: return
        dev.isBlocked = blocked
        saveDevices()
        notifyListeners()

        if (blocked) {
            // 1. Forcefully sever all active proxy/tunnel sockets immediately
            HotspotControllerApp.instance.proxyServer?.terminateClientSockets(clientIp)
            HotspotControllerApp.instance.socksServer?.terminateClientSockets(clientIp)
            // 2. Hardware packet drop via root iptables (if rooted)
            RootFirewallHelper.setClientFirewallBlocked(clientIp, true)
        } else {
            // Remove hardware packet drop
            RootFirewallHelper.setClientFirewallBlocked(clientIp, false)
        }
    }

    /**
     * Rename a device (e.g. "D1 - Rahul's Laptop").
     */
    fun setDeviceCustomName(clientIp: String, name: String) {
        val dev = devicesMap[clientIp] ?: return
        dev.customName = name
        saveDevices()
        notifyListeners()
    }

    /**
     * Remove a device from the managed list.
     */
    fun removeDevice(clientIp: String) {
        devicesMap.remove(clientIp)
        downThrottlersMap.remove(clientIp)
        upThrottlersMap.remove(clientIp)
        saveDevices()
        notifyListeners()
    }

    /**
     * Record downloaded and uploaded bytes for a device.
     */
    fun recordTraffic(clientIp: String, downBytes: Int, upBytes: Int) {
        val dev = devicesMap[clientIp] ?: return
        dev.totalBytesDownloaded += downBytes
        dev.totalBytesUploaded += upBytes
        dev.lastSeenTimestamp = System.currentTimeMillis()
        dev.isOnline = true
    }

    /**
     * Heartbeat check: periodically probes known devices to immediately detect disconnections.
     */
    fun checkDeviceLiveness() {
        val now = System.currentTimeMillis()

        for ((ip, dev) in devicesMap) {
            val idleTimeMs = now - dev.lastSeenTimestamp

            if (idleTimeMs > 20000 && dev.isProxyRouted) {
                dev.isProxyRouted = false
            }

            // Fast asynchronous ping
            livenessExecutor.execute {
                var reachable = false
                try {
                    val addr = InetAddress.getByName(ip)
                    reachable = addr.isReachable(150)
                    if (!reachable) {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(ip, 53), 100)
                        socket.close()
                        reachable = true
                    }
                } catch (_: Exception) {}

                if (reachable) {
                    dev.lastSeenTimestamp = System.currentTimeMillis()
                    if (!dev.isOnline) {
                        dev.isOnline = true
                        notifyListeners()
                    }
                } else if (idleTimeMs > 12000) { // 12 seconds with no response and no traffic
                    if (dev.isOnline) {
                        dev.isOnline = false
                        dev.currentDownSpeedKbps = 0.0
                        dev.currentUpSpeedKbps = 0.0
                        notifyListeners()
                    }
                    // Auto-prune stale devices after 35 seconds of inactivity
                    if (idleTimeMs > 35000 && !dev.isBlocked && dev.speedLimitKbps == 0) {
                        devicesMap.remove(ip)
                        downThrottlersMap.remove(ip)
                        upThrottlersMap.remove(ip)
                        notifyListeners()
                    }
                }
            }
        }
    }

    /**
     * Synchronize with ARP table to obtain hardware MAC addresses.
     */
    fun syncWithArpTable() {
        val arpList = ArpScanner.scanArpTable()
        var changed = false
        for (arp in arpList) {
            val validMac = if (ArpScanner.isValidMac(arp.mac)) arp.mac else ""
            val dev = devicesMap[arp.ip]
            if (dev != null) {
                dev.isOnline = true
                dev.lastSeenTimestamp = System.currentTimeMillis()
                if (validMac.isNotBlank() && dev.macAddress != validMac) {
                    dev.macAddress = validMac
                    val vendor = DeviceNameResolver.lookupMacVendor(validMac)
                    if (vendor != null && dev.manufacturerVendor.isBlank()) {
                        dev.manufacturerVendor = vendor
                    }
                    changed = true
                }
                if (!arp.deviceName.isNullOrBlank() && !arp.deviceName.startsWith("Mobile", ignoreCase = true) && !arp.deviceName.startsWith("Device", ignoreCase = true) && dev.resolvedDeviceName.isBlank()) {
                    dev.resolvedDeviceName = arp.deviceName
                    changed = true
                }
            } else if (getActiveDeviceCount() < maxDeviceLimit) {
                val vendor = if (validMac.isNotBlank()) DeviceNameResolver.lookupMacVendor(validMac) ?: "" else ""
                val cleanHost = if (arp.deviceName != null && !arp.deviceName.startsWith("Mobile", ignoreCase = true) && !arp.deviceName.startsWith("Device", ignoreCase = true)) arp.deviceName else ""
                val newDev = ConnectedDevice(
                    ipAddress = arp.ip,
                    macAddress = validMac,
                    resolvedDeviceName = cleanHost,
                    manufacturerVendor = vendor,
                    osInfo = "Android",
                    isOnline = true,
                    lastSeenTimestamp = System.currentTimeMillis()
                )
                devicesMap[arp.ip] = newDev
                downThrottlersMap[arp.ip] = BandwidthThrottler(0)
                upThrottlersMap[arp.ip] = BandwidthThrottler(0)
                changed = true
            }
        }
        if (changed) {
            saveDevices()
            notifyListeners()
        }
    }

    fun triggerSubnetScan(hostIp: String, onDone: () -> Unit = {}) {
        SubnetScanner.scanSubnet(
            hostIp = hostIp,
            onDeviceFound = { host ->
                registerDiscoveredHost(host.ip, host.hostname)
            },
            onScanComplete = {
                syncWithArpTable()
                checkDeviceLiveness()
                onDone()
            }
        )
    }

    fun updateLiveSpeeds() {
        HotspotTrafficMonitor.updateLiveInterfaceSpeed()
        val ifaceDown = HotspotTrafficMonitor.liveDownSpeedKbps
        val ifaceUp = HotspotTrafficMonitor.liveUpSpeedKbps

        val onlineDevices = devicesMap.values.filter { it.isOnline }

        for ((ip, dev) in devicesMap) {
            val downThrottler = downThrottlersMap[ip]
            val upThrottler = upThrottlersMap[ip]
            downThrottler?.checkIdleDecay()
            upThrottler?.checkIdleDecay()

            if (!dev.isOnline) {
                dev.currentDownSpeedKbps = 0.0
                dev.currentUpSpeedKbps = 0.0
            } else if (dev.isProxyRouted && ((downThrottler?.currentSpeedKbps ?: 0.0) > 0 || (upThrottler?.currentSpeedKbps ?: 0.0) > 0)) {
                dev.currentDownSpeedKbps = downThrottler?.currentSpeedKbps ?: 0.0
                dev.currentUpSpeedKbps = upThrottler?.currentSpeedKbps ?: 0.0
            } else if (onlineDevices.size == 1 && ifaceDown > 0) {
                // Only 1 device online on hotspot: all hotspot traffic belongs to it
                dev.currentDownSpeedKbps = ifaceDown
                dev.currentUpSpeedKbps = ifaceUp
            } else if (onlineDevices.isNotEmpty() && ifaceDown > 0) {
                // Distribute across active online devices
                dev.currentDownSpeedKbps = ifaceDown / onlineDevices.size
                dev.currentUpSpeedKbps = ifaceUp / onlineDevices.size
            } else {
                dev.currentDownSpeedKbps = 0.0
                dev.currentUpSpeedKbps = 0.0
            }
        }
        globalThrottler.checkIdleDecay()
        notifyListeners()
    }

    fun getAllDevices(): List<ConnectedDevice> {
        return devicesMap.values.sortedWith(
            compareByDescending<ConnectedDevice> { it.isOnline }.thenBy { it.ipAddress }
        )
    }

    fun getActiveDevices(): List<ConnectedDevice> {
        return devicesMap.values.filter { it.isOnline }.sortedBy { it.ipAddress }
    }

    fun getTotalHotspotDataMb(): Double {
        return devicesMap.values.sumOf { it.totalDataMb }
    }

    fun getActiveDeviceCount(): Int = devicesMap.values.count { it.isOnline }

    fun getDeviceCount(): Int = devicesMap.size

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }

    private data class SavedDeviceInfo(
        val macAddress: String,
        val customName: String,
        val resolvedDeviceName: String,
        val deviceCategory: String = "Mobile",
        val osInfo: String = "",
        val isBlocked: Boolean,
        val speedLimitKbps: Int,
        val firstConnectedTimestamp: Long = 0L
    )
}
