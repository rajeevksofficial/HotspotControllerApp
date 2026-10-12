package com.hotspot.controller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hotspot.controller.HotspotControllerApp
import com.hotspot.controller.MainActivity
import com.hotspot.controller.R
import com.hotspot.controller.network.DnsSinkholeServer
import com.hotspot.controller.network.HotspotNetworkHelper
import com.hotspot.controller.network.HttpProxyServer

class HotspotProxyService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val CHANNEL_ID = "hotspot_controller_service_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile var isRunning = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var proxyServer: HttpProxyServer? = null
    private var socksServer: com.hotspot.controller.network.Socks5ProxyServer? = null
    private var dnsServer: DnsSinkholeServer? = null
    private var tickCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServiceInternal()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startServiceInternal()
            }
        }
        return START_STICKY
    }

    private fun startServiceInternal() {
        if (isRunning) return
        isRunning = true

        val app = HotspotControllerApp.instance

        // 1. Start HTTP/HTTPS Proxy Server (Port 8080)
        proxyServer = HttpProxyServer(
            port = 8080,
            deviceManager = app.deviceManager,
            urlRuleManager = app.urlRuleManager,
            accessLogManager = app.accessLogManager
        )
        app.proxyServer = proxyServer
        proxyServer?.start()

        // 2. Start SOCKS5 Proxy Server (Port 1080)
        socksServer = com.hotspot.controller.network.Socks5ProxyServer(
            port = 1080,
            deviceManager = app.deviceManager,
            urlRuleManager = app.urlRuleManager,
            accessLogManager = app.accessLogManager
        )
        app.socksServer = socksServer
        socksServer?.start()

        // 3. Start DNS Sinkhole Server (UDP Port 5353)
        dnsServer = DnsSinkholeServer(
            port = 5353,
            deviceManager = app.deviceManager,
            urlRuleManager = app.urlRuleManager,
            accessLogManager = app.accessLogManager
        )
        app.dnsServer = dnsServer
        dnsServer?.start()

        // Initial device sweep
        val hostIp = HotspotNetworkHelper.getHotspotOrLocalIp()
        app.deviceManager.triggerSubnetScan(hostIp)

        val notification = buildNotification(0, 0.0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startPeriodicUpdates()
    }

    private fun startPeriodicUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                val app = HotspotControllerApp.instance
                tickCount++

                app.deviceManager.checkDeviceLiveness()
                app.deviceManager.updateLiveSpeeds()

                // Perform active subnet sweep every ~8 ticks (every ~12 seconds) to catch newly connected phones
                if (tickCount % 8 == 0) {
                    val hostIp = HotspotNetworkHelper.getHotspotOrLocalIp()
                    app.deviceManager.triggerSubnetScan(hostIp)
                }

                val activeDeviceCount = app.deviceManager.getActiveDeviceCount()
                val totalSpeed = app.deviceManager.getAllDevices().filter { it.isOnline }.sumOf { it.currentDownSpeedKbps }
                val blockedCount = app.accessLogManager.totalBlockedCount

                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, buildNotification(activeDeviceCount, totalSpeed, blockedCount))

                handler.postDelayed(this, 1500)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun buildNotification(deviceCount: Int, speedKbps: Double, blockedCount: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, HotspotProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speedText = if (speedKbps > 1024) {
            String.format("%.1f Mbps", speedKbps / 1024.0)
        } else {
            String.format("%.0f Kbps", speedKbps)
        }

        val content = "Devices: $deviceCount/10 | Speed: $speedText | Blocked: $blockedCount URLs"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hotspot Controller Active")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_hotspot)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_block, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hotspot Controller Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors connected hotspot devices, controls speeds, and blocks URLs."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun stopServiceInternal() {
        isRunning = false
        updateRunnable?.let { handler.removeCallbacks(it) }
        proxyServer?.stop()
        socksServer?.stop()
        dnsServer?.stop()
        HotspotControllerApp.instance.proxyServer = null
        HotspotControllerApp.instance.socksServer = null
        HotspotControllerApp.instance.dnsServer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServiceInternal()
        super.onDestroy()
    }
}
