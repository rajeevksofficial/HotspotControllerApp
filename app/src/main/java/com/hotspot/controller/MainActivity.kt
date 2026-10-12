package com.hotspot.controller

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.hotspot.controller.databinding.ActivityMainBinding
import com.hotspot.controller.network.HotspotNetworkHelper
import com.hotspot.controller.network.RootFirewallHelper
import com.hotspot.controller.network.SubnetScanner
import com.hotspot.controller.service.HotspotProxyService
import com.hotspot.controller.ui.DeviceAdapter
import com.hotspot.controller.ui.LiveRequestAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var liveRequestAdapter: LiveRequestAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var uiUpdateRunnable: Runnable? = null
    private var isRootModeActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissionsIfNecessary()
        setupTabs()
        setupAdapters()
        setupDashboardView()
        setupDevicesView()
        setupLiveRequestsView()
        setupSetupGuideView()
        startPeriodicUiRefresh()

        // Trigger initial scan on startup
        performSubnetScan(silent = true)
    }

    private fun requestPermissionsIfNecessary() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                binding.viewDashboard.root.visibility = if (tab?.position == 0) View.VISIBLE else View.GONE
                binding.viewDevices.root.visibility = if (tab?.position == 1) View.VISIBLE else View.GONE
                binding.viewLiveRequests.root.visibility = if (tab?.position == 2) View.VISIBLE else View.GONE
                binding.viewSetupGuide.root.visibility = if (tab?.position == 3) View.VISIBLE else View.GONE

                if (tab?.position == 1) {
                    refreshDevicesList()
                } else if (tab?.position == 2) {
                    refreshLiveRequests()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupAdapters() {
        val app = HotspotControllerApp.instance

        // Device Adapter with delete callback
        deviceAdapter = DeviceAdapter(
            context = this,
            onSpeedLimitChanged = { ip, limit ->
                app.deviceManager.setDeviceSpeedLimit(ip, limit)
                refreshDevicesList()
                val label = if (limit == 0) "Unlimited" else "$limit Kbps"
                val dev = app.deviceManager.getAllDevices().find { it.ipAddress == ip }
                if (dev?.isProxyRouted == true) {
                    Toast.makeText(this, "⚡ Speed for $ip capped at $label via Gateway!", Toast.LENGTH_SHORT).show()
                } else if (!RootFirewallHelper.isDeviceRooted() && limit > 0) {
                    showNonRootSpeedGuidanceDialog(ip, label)
                } else {
                    Toast.makeText(this, "Speed for $ip set to $label", Toast.LENGTH_SHORT).show()
                }
            },
            onBlockStatusChanged = { ip, isBlocked ->
                app.deviceManager.setDeviceBlocked(ip, isBlocked)
                refreshDevicesList()
                val isRooted = RootFirewallHelper.isDeviceRooted()
                if (isBlocked) {
                    if (isRooted) {
                        Toast.makeText(this, "⚡ Hardware packet drop applied for $ip! Internet severed.", Toast.LENGTH_SHORT).show()
                    } else {
                        val dev = app.deviceManager.getAllDevices().find { it.ipAddress == ip }
                        if (dev?.isProxyRouted == true) {
                            Toast.makeText(this, "⚡ Internet paused for $ip! All connections severed immediately.", Toast.LENGTH_SHORT).show()
                        } else {
                            showNonRootPauseGuidanceDialog(ip)
                        }
                    }
                } else {
                    Toast.makeText(this, "Internet resumed for $ip", Toast.LENGTH_SHORT).show()
                }
            },
            onRenameDevice = { ip, newName ->
                app.deviceManager.setDeviceCustomName(ip, newName)
                refreshDevicesList()
            },
            onDeleteDevice = { ip ->
                app.deviceManager.removeDevice(ip)
                refreshDevicesList()
                Toast.makeText(this, "Removed $ip", Toast.LENGTH_SHORT).show()
            },
            onShowSetupGuide = { ip ->
                showNonRootPauseGuidanceDialog(ip)
            }
        )
        binding.viewDevices.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.viewDevices.rvDevices.adapter = deviceAdapter

        // Live Request Adapter
        liveRequestAdapter = LiveRequestAdapter()
        binding.viewLiveRequests.rvLiveRequests.layoutManager = LinearLayoutManager(this)
        binding.viewLiveRequests.rvLiveRequests.adapter = liveRequestAdapter
    }

    private fun setupDashboardView() {
        val app = HotspotControllerApp.instance

        // Toggle Gateway Service
        binding.viewDashboard.btnToggleGateway.setOnClickListener {
            if (HotspotProxyService.isRunning) {
                val intent = Intent(this, HotspotProxyService::class.java).apply {
                    action = HotspotProxyService.ACTION_STOP
                }
                startService(intent)
            } else {
                val intent = Intent(this, HotspotProxyService::class.java).apply {
                    action = HotspotProxyService.ACTION_START
                }
                ContextCompat.startForegroundService(this, intent)
                performSubnetScan(silent = false)
            }
            handler.postDelayed({ updateDashboardStats() }, 300)
        }

        // Open Hotspot Settings
        binding.viewDashboard.btnOpenHotspotSettings.setOnClickListener {
            try {
                startActivity(HotspotNetworkHelper.getTetheringSettingsIntent())
            } catch (_: Exception) {
                try {
                    startActivity(HotspotNetworkHelper.getWirelessSettingsIntent())
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open Hotspot settings: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Global Speed Limit Buttons
        fun updateGlobalSpeed(limitKbps: Int) {
            app.deviceManager.globalSpeedLimitKbps = limitKbps
            updateGlobalSpeedButtonHighlights(limitKbps)
            val label = if (limitKbps == 0) "Unlimited" else if (limitKbps >= 1024) "${limitKbps / 1024} Mbps" else "$limitKbps Kbps"
            binding.viewDashboard.tvGlobalSpeedValue.text = label
            Toast.makeText(this, "Global Speed Limit set to $label", Toast.LENGTH_SHORT).show()
        }

        binding.viewDashboard.btnSpeedUnlimited.setOnClickListener { updateGlobalSpeed(0) }
        binding.viewDashboard.btnSpeed256k.setOnClickListener { updateGlobalSpeed(256) }
        binding.viewDashboard.btnSpeed512k.setOnClickListener { updateGlobalSpeed(512) }
        binding.viewDashboard.btnSpeed1m.setOnClickListener { updateGlobalSpeed(1024) }
        binding.viewDashboard.btnSpeed2m.setOnClickListener { updateGlobalSpeed(2048) }
        binding.viewDashboard.btnSpeed5m.setOnClickListener { updateGlobalSpeed(5120) }

        updateGlobalSpeedButtonHighlights(app.deviceManager.globalSpeedLimitKbps)
    }

    private fun updateGlobalSpeedButtonHighlights(currentLimit: Int) {
        val d = binding.viewDashboard
        fun highlight(btn: MaterialButton, active: Boolean) {
            if (active) {
                btn.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
                btn.setTextColor(ContextCompat.getColor(this, R.color.white))
            } else {
                btn.setBackgroundColor(ContextCompat.getColor(this, R.color.transparent))
                btn.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
        }

        highlight(d.btnSpeedUnlimited, currentLimit <= 0)
        highlight(d.btnSpeed256k, currentLimit == 256)
        highlight(d.btnSpeed512k, currentLimit == 512)
        highlight(d.btnSpeed1m, currentLimit == 1024)
        highlight(d.btnSpeed2m, currentLimit == 2048)
        highlight(d.btnSpeed5m, currentLimit == 5120)

        val label = if (currentLimit <= 0) "Unlimited" else if (currentLimit >= 1024) "${currentLimit / 1024} Mbps" else "$currentLimit Kbps"
        d.tvGlobalSpeedValue.text = label
    }

    private fun setupDevicesView() {
        val app = HotspotControllerApp.instance

        binding.viewDevices.btnRefreshDevices.setOnClickListener {
            performSubnetScan(silent = false)
        }

        binding.viewDevices.btnEmptyScan.setOnClickListener {
            performSubnetScan(silent = false)
        }

        // Add Device manually dialog
        binding.viewDevices.btnAddDeviceManual.setOnClickListener {
            showAddDeviceDialog()
        }

        // Filter chips (All, Active Connected, Paused)
        binding.viewDevices.chipFilterAll.setOnClickListener { refreshDevicesList() }
        binding.viewDevices.chipFilterActive.setOnClickListener { refreshDevicesList() }
        binding.viewDevices.chipFilterPaused.setOnClickListener { refreshDevicesList() }
    }

    private fun performSubnetScan(silent: Boolean) {
        val app = HotspotControllerApp.instance
        val hostIp = HotspotNetworkHelper.getHotspotOrLocalIp()
        binding.viewDevices.progressBarScanning.visibility = View.VISIBLE

        if (!silent) {
            Toast.makeText(this, "Scanning hotspot subnet ($hostIp)...", Toast.LENGTH_SHORT).show()
        }

        app.deviceManager.triggerSubnetScan(hostIp) {
            runOnUiThread {
                binding.viewDevices.progressBarScanning.visibility = View.GONE
                refreshDevicesList()
                if (!silent) {
                    val count = app.deviceManager.getDeviceCount()
                    Toast.makeText(this, "Scan complete: $count device(s) detected", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAddDeviceDialog() {
        val app = HotspotControllerApp.instance
        val hostIp = HotspotNetworkHelper.getHotspotOrLocalIp()
        val defaultPrefix = if (hostIp.contains(".")) hostIp.substringBeforeLast(".") + "." else "192.168.43."

        val input = EditText(this).apply {
            hint = "e.g. ${defaultPrefix}50"
            setText(defaultPrefix)
            setSelection(text.length)
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Device Manually")
            .setMessage("Enter the IP address of the connected device:")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotBlank() && ip.contains(".")) {
                    app.deviceManager.registerDiscoveredHost(ip, null)
                    refreshDevicesList()
                    Toast.makeText(this, "Device $ip added!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid IP address", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNonRootPauseGuidanceDialog(clientIp: String) {
        val hostIp = HotspotNetworkHelper.getHotspotOrLocalIp()
        AlertDialog.Builder(this)
            .setTitle("⏸️ Pause Internet for $clientIp")
            .setMessage(
                "Internet for $clientIp is marked PAUSED in the controller.\n\n" +
                "On standard non-rooted phones & laptops, client traffic must be routed to D0's gateway to enforce pause and sever internet:\n\n" +
                "Option 1 (Recommended - Manual Proxy):\n" +
                "• Wi-Fi Settings on $clientIp > Modify Network > Advanced\n" +
                "• Proxy: Manual\n" +
                "• Proxy Host: $hostIp\n" +
                "• Proxy Port: 8080 (or SOCKS5 Port: 1080)\n\n" +
                "Option 2 (Auto-Config PAC):\n" +
                "• Proxy: Auto-Config\n" +
                "• PAC URL: http://$hostIp:8080/proxy.pac\n\n" +
                "Once pointed to D0, 100% of internet traffic for $clientIp will be immediately blocked and severed!"
            )
            .setPositiveButton("Copy $hostIp:8080") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Proxy", "$hostIp:8080"))
                Toast.makeText(this, "Copied $hostIp:8080 to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Copy PAC URL") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PAC URL", "http://$hostIp:8080/proxy.pac"))
                Toast.makeText(this, "Copied PAC URL to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun showNonRootSpeedGuidanceDialog(clientIp: String, speedLabel: String) {
        val hostIp = HotspotNetworkHelper.getHotspotOrLocalIp()
        AlertDialog.Builder(this)
            .setTitle("⚡ Speed Control: $speedLabel for $clientIp")
            .setMessage(
                "Speed limit is set to $speedLabel for $clientIp.\n\n" +
                "On standard non-rooted phones & laptops, client traffic must be routed through D0's gateway to enforce bandwidth throttling:\n\n" +
                "1. On $clientIp, open Wi-Fi settings.\n" +
                "2. Tap the connected Wi-Fi > Modify Network / Advanced.\n" +
                "3. Set Proxy to 'Manual':\n" +
                "   • Proxy Host: $hostIp\n" +
                "   • Proxy Port: 8080\n" +
                "4. Save.\n\n" +
                "D0 will then strictly cap download & upload speed to $speedLabel across all apps, browsers and streams!"
            )
            .setPositiveButton("Copy $hostIp:8080") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Proxy", "$hostIp:8080"))
                Toast.makeText(this, "Copied $hostIp:8080 to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun setupSetupGuideView() {
        val ip = HotspotNetworkHelper.getHotspotOrLocalIp()
        fun copy(label: String, text: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            Toast.makeText(this, "Copied $text to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.viewSetupGuide.tvGuideProxyHost.text = "Proxy Hostname: $ip"
        binding.viewSetupGuide.tvGuideProxyHost.setOnClickListener { copy("Hotspot IP", ip) }
        binding.viewSetupGuide.tvGuideHttpPort.setOnClickListener { copy("HTTP Proxy", "$ip:8080") }
        binding.viewSetupGuide.tvGuideSocksPort.setOnClickListener { copy("SOCKS5 Proxy", "$ip:1080") }
        binding.viewSetupGuide.tvGuidePacUrl.text = "Auto-Config PAC: http://$ip:8080/proxy.pac"
        binding.viewSetupGuide.tvGuidePacUrl.setOnClickListener { copy("PAC URL", "http://$ip:8080/proxy.pac") }

        // Check root status
        val isRooted = RootFirewallHelper.isDeviceRooted()
        binding.viewSetupGuide.tvRootStatus.text = if (isRooted) "Root: Available" else "Root: Not Detected"
        binding.viewSetupGuide.tvRootStatus.setTextColor(
            ContextCompat.getColor(this, if (isRooted) R.color.status_green else R.color.text_muted)
        )

        binding.viewSetupGuide.btnToggleRootMode.setOnClickListener {
            if (!isRooted) {
                Toast.makeText(this, "This device is not rooted. Use Proxy Setup instructions above.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (isRootModeActive) {
                RootFirewallHelper.disableTransparentProxy(8080, 5353)
                isRootModeActive = false
                binding.viewSetupGuide.btnToggleRootMode.text = "Enable Root Transparent Redirection"
                Toast.makeText(this, "Transparent redirection disabled", Toast.LENGTH_SHORT).show()
            } else {
                val ok = RootFirewallHelper.enableTransparentProxy(8080, 5353)
                if (ok) {
                    isRootModeActive = true
                    binding.viewSetupGuide.btnToggleRootMode.text = "Disable Root Transparent Redirection"
                    Toast.makeText(this, "Transparent redirection enabled! Clients don't need manual proxy.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Root permission denied or failed to apply iptables", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startPeriodicUiRefresh() {
        uiUpdateRunnable = object : Runnable {
            override fun run() {
                updateDashboardStats()
                refreshDevicesList()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(uiUpdateRunnable!!)
    }

    private fun updateDashboardStats() {
        val app = HotspotControllerApp.instance
        val isRunning = HotspotProxyService.isRunning
        val ip = HotspotNetworkHelper.getHotspotOrLocalIp()

        binding.viewDashboard.tvM1IpAddress.text = ip
        binding.viewSetupGuide.tvGuideProxyHost.text = "Proxy Hostname: $ip"

        if (isRunning) {
            binding.tvStatusBadge.text = "ACTIVE"
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_allowed)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_green))

            binding.viewDashboard.ivGatewayIcon.setBackgroundResource(R.drawable.bg_badge_allowed)
            binding.viewDashboard.ivGatewayIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_green))
            binding.viewDashboard.tvGatewayStatusTitle.text = "Hotspot Gateway Active"
            binding.viewDashboard.tvGatewayStatusDesc.text = "Controlling speed & filtering URLs on port 8080"
            binding.viewDashboard.btnToggleGateway.text = "Stop Gateway"
            binding.viewDashboard.btnToggleGateway.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red))
            binding.viewDashboard.btnToggleGateway.setIconResource(R.drawable.ic_pause)
        } else {
            binding.tvStatusBadge.text = "STOPPED"
            binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_blocked)
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_red))

            binding.viewDashboard.ivGatewayIcon.setBackgroundResource(R.drawable.bg_pill)
            binding.viewDashboard.ivGatewayIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            binding.viewDashboard.tvGatewayStatusTitle.text = "Controller Gateway Inactive"
            binding.viewDashboard.tvGatewayStatusDesc.text = "Start gateway to control client internet speeds & URLs"
            binding.viewDashboard.btnToggleGateway.text = "Start Gateway"
            binding.viewDashboard.btnToggleGateway.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.viewDashboard.btnToggleGateway.setIconResource(R.drawable.ic_play)
        }

        // Control mode banner
        val isRooted = RootFirewallHelper.isDeviceRooted()
        if (isRooted && isRootModeActive) {
            binding.viewDashboard.tvControlModeIcon.text = "⚡"
            binding.viewDashboard.tvControlModeText.text = "Root Transparent Mode ACTIVE: Hardware interception running. Clients D1–D10 controlled without manual proxy."
        } else if (isRooted) {
            binding.viewDashboard.tvControlModeIcon.text = "⚡"
            binding.viewDashboard.tvControlModeText.text = "Root available! Tap 'Setup Guide' tab to enable 1-Click Transparent Redirection (zero proxy setup on D1–D10)."
        } else {
            binding.viewDashboard.tvControlModeIcon.text = "ℹ️"
            binding.viewDashboard.tvControlModeText.text = "Proxy Mode: To govern client speed, set client Wi-Fi Proxy to $ip:8080 (or visit http://$ip:8080 on client to verify)."
        }

        val allDevices = app.deviceManager.getAllDevices()
        val activeCount = app.deviceManager.getActiveDeviceCount()
        val maxLimit = app.deviceManager.maxDeviceLimit
        binding.viewDashboard.tvClientDevicesCount.text = "$activeCount / $maxLimit Connected"
        binding.viewDevices.tvDeviceCountSub.text = "$activeCount Online • ${allDevices.size} Tracked • Max limit: $maxLimit devices"

        val pausedCount = allDevices.count { it.isBlocked }
        binding.viewDashboard.tvBlockedUrlsCountSummary.text = if (pausedCount > 0) "$pausedCount Device(s) Paused" else "All Internet Active"

        // True hardware hotspot speed calculation using HotspotTrafficMonitor
        val ifaceDown = com.hotspot.controller.network.HotspotTrafficMonitor.liveDownSpeedKbps
        val ifaceUp = com.hotspot.controller.network.HotspotTrafficMonitor.liveUpSpeedKbps
        val proxyDown = allDevices.filter { it.isOnline }.sumOf { it.currentDownSpeedKbps }
        val proxyUp = allDevices.filter { it.isOnline }.sumOf { it.currentUpSpeedKbps }

        val totalDownKbps = if (ifaceDown > 0) ifaceDown else proxyDown
        val totalUpKbps = if (ifaceUp > 0) ifaceUp else proxyUp

        binding.viewDashboard.tvLiveDownSpeed.text = if (totalDownKbps >= 1024) {
            String.format("%.1f Mbps", totalDownKbps / 1024.0)
        } else {
            String.format("%.0f Kbps", totalDownKbps)
        }

        binding.viewDashboard.tvLiveUpSpeed.text = if (totalUpKbps >= 1024) {
            String.format("%.1f Mbps", totalUpKbps / 1024.0)
        } else {
            String.format("%.0f Kbps", totalUpKbps)
        }

        val ifaceBytes = com.hotspot.controller.network.HotspotTrafficMonitor.totalDownBytes + com.hotspot.controller.network.HotspotTrafficMonitor.totalUpBytes
        val totalDataMb = if (ifaceBytes > 0) {
            ifaceBytes / (1024.0 * 1024.0)
        } else {
            allDevices.sumOf { it.totalDataMb }
        }
        binding.viewDashboard.tvTotalDataUsage.text = String.format("Total Shared: %.2f MB", totalDataMb)
    }

    private fun setupLiveRequestsView() {
        val app = HotspotControllerApp.instance

        binding.viewLiveRequests.btnClearRequests.setOnClickListener {
            app.accessLogManager.clear()
            refreshLiveRequests()
            Toast.makeText(this, "Live requests cleared", Toast.LENGTH_SHORT).show()
        }

        binding.viewLiveRequests.etSearchRequest.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshLiveRequests()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        app.accessLogManager.addChangeListener {
            runOnUiThread {
                refreshLiveRequests()
            }
        }
    }

    private fun refreshLiveRequests() {
        val app = HotspotControllerApp.instance
        var logs = app.accessLogManager.getLogs()
        val totalCount = logs.size
        val query = binding.viewLiveRequests.etSearchRequest.text.toString().trim().lowercase()

        if (query.isNotEmpty()) {
            logs = logs.filter {
                it.targetHost.lowercase().contains(query) ||
                it.clientIp.contains(query) ||
                it.deviceName.lowercase().contains(query) ||
                it.protocol.lowercase().contains(query)
            }
        }

        binding.viewLiveRequests.tvLiveRequestCount.text = "$totalCount Captured in Real-Time"
        binding.viewLiveRequests.layoutEmptyRequests.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        binding.viewLiveRequests.rvLiveRequests.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE

        liveRequestAdapter.submitList(logs)

        if (binding.viewLiveRequests.switchAutoScroll.isChecked && logs.isNotEmpty()) {
            binding.viewLiveRequests.rvLiveRequests.scrollToPosition(0)
        }
    }

    private fun refreshDevicesList() {
        val app = HotspotControllerApp.instance
        val allDevices = app.deviceManager.getAllDevices()

        val activeCount = allDevices.count { it.isOnline }
        val pausedCount = allDevices.count { it.isBlocked }
        binding.viewDevices.chipFilterAll.text = "All Devices (${allDevices.size})"
        binding.viewDevices.chipFilterActive.text = "● Active Connected ($activeCount)"
        binding.viewDevices.chipFilterPaused.text = "Paused ($pausedCount)"

        val filteredDevices = when {
            binding.viewDevices.chipFilterActive.isChecked -> allDevices.filter { it.isOnline }
            binding.viewDevices.chipFilterPaused.isChecked -> allDevices.filter { it.isBlocked }
            else -> allDevices
        }

        deviceAdapter.submitList(filteredDevices)

        val isScanning = SubnetScanner.isScanInProgress()
        binding.viewDevices.progressBarScanning.visibility = if (isScanning) View.VISIBLE else View.GONE
        binding.viewDevices.layoutEmptyDevices.visibility = if (filteredDevices.isEmpty()) View.VISIBLE else View.GONE
        binding.viewDevices.rvDevices.visibility = if (filteredDevices.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        uiUpdateRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }
}
