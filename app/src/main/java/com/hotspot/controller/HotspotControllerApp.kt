package com.hotspot.controller

import android.app.Application
import com.hotspot.controller.network.AccessLogManager
import com.hotspot.controller.network.DeviceManager
import com.hotspot.controller.network.DnsSinkholeServer
import com.hotspot.controller.network.HttpProxyServer
import com.hotspot.controller.network.Socks5ProxyServer
import com.hotspot.controller.network.UrlRuleManager

class HotspotControllerApp : Application() {

    lateinit var deviceManager: DeviceManager
        private set
    lateinit var urlRuleManager: UrlRuleManager
        private set
    lateinit var accessLogManager: AccessLogManager
        private set
    var proxyServer: HttpProxyServer? = null
    var socksServer: Socks5ProxyServer? = null
    var dnsServer: DnsSinkholeServer? = null

    companion object {
        lateinit var instance: HotspotControllerApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        deviceManager = DeviceManager(this)
        urlRuleManager = UrlRuleManager(this)
        accessLogManager = AccessLogManager()
    }
}
