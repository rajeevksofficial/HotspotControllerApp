# 📱 Hotspot Controller (D0 Host Gateway)

An Android application enabling **Host Device D0** (the Hotspot Host) to discover, monitor, throttle internet speed, and pause/resume internet access for connected client devices (**D1, D2, D3... up to D10**), designed to work on **both Non-Rooted and Rooted Android devices**.

---

## ✨ Key Features

### 1. Real-Time Connected Devices (`D1`–`D10`)
- Clean client numbering (`D1`, `D2`, ... up to `D10`) with optional custom nicknames.
- **Automatic OS Identification**: Detects and displays the connected client's operating system (`Android`, `Windows`, `Mac`, `iOS`, `Linux`) via NetBIOS, SMB probing, DNS traffic fingerprinting, and HTTP/HTTPS User-Agent inspection.
- **Conditional MAC Address Display**: Displays hardware MAC addresses whenever available without showing placeholder `Unknown` text.
- **Live Telemetry**: Tracks connection time, live bandwidth speed, and cumulative download/upload data usage per client.

### 2. Per-Device & Global Speed Control (256 Kbps, 512 Kbps, 1 Mbps, 2 Mbps)
- **Multi-Threaded Leaky Bucket Rate Limiter (`BandwidthThrottler.kt`)**:
  - Uses time-slice reservation across concurrent streams so cumulative throughput per device is strictly capped at the selected rate (e.g., $\le 256$ Kbps).
  - Separate Download and Upload throttlers prevent upload acknowledgments from starving download streams.

### 3. Instant Internet Pause / Resume Killswitch
- Toggling internet access OFF for any client (`D1`–`D10`) immediately severs active HTTP (`8080`) and SOCKS5 (`1080`) proxy tunnels and blocks new connections (`403 Forbidden`), with hardware `iptables` drop rules applied automatically on rooted hosts.

### 4. Live Requests Stream
- Real-time auto-scrolling feed of HTTP, HTTPS, SOCKS5, and DNS requests across all connected devices with instant domain/IP search filtering.

---

## 🚀 How to Use on Non-Rooted Devices

### Step 1: On Host Device (D0)
1. Install [`apk/HotspotController.apk`](apk/HotspotController.apk) on **D0**.
2. Turn ON **Personal Wi-Fi Hotspot** on **D0**.
3. Open **Hotspot Controller** and tap **Start Controller Gateway**.

### Step 2: On Client Device (D1–D10)
1. Connect **D1** to **D0**'s Wi-Fi hotspot.
2. Open **Wi-Fi Settings** on **D1** $\rightarrow$ Tap the connected hotspot network $\rightarrow$ **Modify / Advanced Options**.
3. Set **Proxy** to **Manual**:
   - **Proxy Hostname**: `192.168.43.1` (or the Gateway IP displayed on D0's Dashboard)
   - **Proxy Port**: `8080` *(or Auto-Config PAC URL: `http://192.168.43.1:8080/proxy.pac`)*
4. Save changes. **D1** will now show as `● Controlled (8080 Active)` in D0's Devices list.
