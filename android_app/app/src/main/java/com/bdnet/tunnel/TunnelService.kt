package com.bdnet.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.bdnet.tunnel.model.TunnelConfig
import com.bdnet.tunnel.tunnel.*
import com.bdnet.tunnel.util.Logger
import java.net.HttpURLConnection
import java.net.URL

class TunnelService : VpnService() {
    private val binder = LocalBinder()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isVpnRunning = false

    private var wsClient: WsTunnelClient? = null
    private var vlessClient: VlessClient? = null
    private var dnsClient: DnsTunnelClient? = null
    private var sniClient: SniInjectClient? = null
    private var sshClient: SshWsClient? = null
    private var httpProxyClient: HttpConnectClient? = null

    private val handler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null

    inner class LocalBinder : Binder() {
        fun getService(): TunnelService = this@TunnelService
    }

    override fun onBind(intent: Intent?): IBinder {
        if (intent != null && SERVICE_INTERFACE == intent.action) {
            return super.onBind(intent)!!
        }
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val config = TunnelConfig()
            intent.getStringExtra("selectedMethod")?.let { config.selectedMethod = it }
            intent.getStringExtra("serverUrl")?.let { config.serverUrl = it }
            intent.getStringExtra("wsUrl")?.let { config.wsUrl = it }
            intent.getStringExtra("vlessUrl")?.let { config.vlessUrl = it }
            intent.getStringExtra("sshWsUrl")?.let { config.sshWsUrl = it }

            startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
            startTunnel(config)
        } else if (action == ACTION_STOP) {
            stopTunnel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    fun startTunnel(config: TunnelConfig) {
        stopTunnel()
        instance = this
        Logger.resetStats()

        val isDns = config.selectedMethod.contains("Method 1") || config.selectedMethod.contains("DNS")

        Thread {
            // ── Step 1: Verify server BEFORE creating TUN interface ──────────
            // We use a normal (non-VPN-protected) connection here because the
            // TUN interface does not exist yet — socket protection not needed.
            if (!isDns) {
                Logger.log("VERIFY", "Pinging server: ${config.serverUrl}/health")
                Logger.setConnectionState(false, "VERIFYING SERVER...")
                val ok = pingServerHttp(config.serverUrl)
                if (!ok) {
                    Logger.log("VERIFY", "Server unreachable at ${config.serverUrl}")
                    Logger.log("VERIFY", "Check: Is the server URL correct? Is it woken up?")
                    Logger.setConnectionState(false, "VERIFICATION FAILED")
                    return@Thread
                }
                Logger.log("VERIFY", "Server responded OK — proceeding to establish VPN interface.")
            } else {
                Logger.log("VERIFY", "DNS Tunnel selected — skipping HTTP verification, using UDP 53 directly.")
            }

            // ── Step 2: Establish TUN interface for VPN ──────────────────────
            // CRITICAL FIX: Only establish TUN if we have a method that
            // uses a local SOCKS proxy (WS, SSH) that needs traffic routing.
            // DNS tunneling works on its own socket with no TUN needed yet.
            val needsTun = !isDns

            if (needsTun) {
                val tunOk = startVpnInterface()
                if (!tunOk) {
                    Logger.log("VPN", "TUN interface could not be created — aborting.")
                    Logger.setConnectionState(false, "VPN INIT FAILED")
                    return@Thread
                }
            }

            // ── Step 3: Start the selected tunnel protocol engine ─────────────
            Logger.log("SERVICE", "Starting tunnel engine: ${config.selectedMethod}")
            startForeground(NOTIFICATION_ID, createNotification("Active: ${config.selectedMethod}"))

            when {
                isDns -> {
                    dnsClient = DnsTunnelClient(config).also { it.start(this) }
                }
                config.selectedMethod.contains("Method 2") || config.selectedMethod.contains("WebSocket") -> {
                    wsClient = WsTunnelClient(config).also { it.start(this) }
                }
                config.selectedMethod.contains("Method 3") || config.selectedMethod.contains("SSH") -> {
                    sshClient = SshWsClient(config).also { it.start(this) }
                }
                config.selectedMethod.contains("Method 4") || config.selectedMethod.contains("SNI") -> {
                    sniClient = SniInjectClient(config).also { it.start(this) }
                }
                config.selectedMethod.contains("Method 5") || config.selectedMethod.contains("VLESS") -> {
                    vlessClient = VlessClient(config).also { it.start(this) }
                }
                config.selectedMethod.contains("Method 6") || config.selectedMethod.contains("HTTP") -> {
                    httpProxyClient = HttpConnectClient(config).also { it.start(this) }
                }
                else -> {
                    wsClient = WsTunnelClient(config).also { it.start(this) }
                }
            }

            startStatsLoop()
        }.start()
    }

    /**
     * Verify server is reachable using a plain Java HttpURLConnection — no VPN
     * protect() needed since the TUN does NOT exist yet at this point.
     * Uses a generous 20-second timeout to handle Render cold-start delays.
     */
    private fun pingServerHttp(serverUrl: String): Boolean {
        return try {
            val base = serverUrl.trimEnd('/')
            val healthUrl = "$base/health"
            Logger.log("VERIFY", "Checking: $healthUrl")
            val conn = URL(healthUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000    // 20 sec — handles Render cold start
            conn.readTimeout = 20_000
            conn.requestMethod = "GET"
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            Logger.log("VERIFY", "Server replied HTTP $code")
            code in 200..499   // Any HTTP response means server is alive
        } catch (e: Exception) {
            Logger.log("VERIFY", "Ping failed: ${e.message}")
            false
        }
    }

    /**
     * Build the Android TUN interface. Returns true if successful.
     * IMPORTANT: Packets read from TUN are NOT forwarded here yet — the
     * local SOCKS proxy on port 1080 is the actual data path. The TUN
     * interface is established so Android VPN key icon appears and the OS
     * knows to route traffic. Full tun2socks bridging is a future phase.
     */
    private fun startVpnInterface(): Boolean {
        return try {
            val builder = Builder()
                .setSession("BDNET Tunnel")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                isVpnRunning = true
                Logger.log("VPN", "TUN interface established (10.0.0.2/24). VPN active.")
                true
            } else {
                Logger.log("VPN", "TUN establish returned null — permission may have been revoked.")
                false
            }
        } catch (e: Exception) {
            Logger.log("VPN", "TUN error: ${e.message}")
            false
        }
    }

    private fun startStatsLoop() {
        statsRunnable = object : Runnable {
            override fun run() {
                Logger.updateStreams(if (isVpnRunning) 1 else 0)
                if (Logger.isConnected) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(statsRunnable!!)
    }

    fun stopTunnel() {
        Logger.log("SERVICE", "Stopping active VPN tunnel...")
        isVpnRunning = false
        statsRunnable?.let { handler.removeCallbacks(it) }

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {}

        wsClient?.stop(); wsClient = null
        vlessClient?.stop(); vlessClient = null
        dnsClient?.stop(); dnsClient = null
        sniClient?.stop(); sniClient = null
        sshClient?.stop(); sshClient = null
        httpProxyClient?.stop(); httpProxyClient = null

        Logger.setConnectionState(false, "DISCONNECTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTunnel()
        instance = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BDNET Tunnel Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BDNET Tunnel")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        var instance: TunnelService? = null
            private set

        const val CHANNEL_ID = "BDNET_TUNNEL_CHANNEL"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.bdnet.tunnel.START"
        const val ACTION_STOP = "com.bdnet.tunnel.STOP"
    }
}
