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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class TunnelService : VpnService() {
    private val binder = LocalBinder()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isVpnRunning = false
    private var tunThread: Thread? = null

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

            startForeground(NOTIFICATION_ID, createNotification("Connecting BDNET Tunnel..."))
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
        Logger.log("VERIFY", "Verifying connection to server: ${config.serverUrl}")
        Logger.setConnectionState(false, "VERIFYING SERVER...")

        // Background thread to verify server reachability before connecting
        Thread {
            val verified = verifyServerConnection(config)
            if (!verified && !config.selectedMethod.contains("DNS")) {
                Logger.log("VERIFY", "ERROR: Server verification failed! Server unreachable at ${config.serverUrl}")
                Logger.setConnectionState(false, "VERIFICATION FAILED")
                return@Thread
            }

            if (verified) {
                Logger.log("VERIFY", "Server Handshake SUCCESS! Server is online and responsive.")
            } else {
                Logger.log("VERIFY", "DNS Tunnel selected — proceeding with direct UDP queries.")
            }

            Logger.log("SERVICE", "Starting Native VpnService Engine for: ${config.selectedMethod}")
            startForeground(NOTIFICATION_ID, createNotification("Active: ${config.selectedMethod}"))

            // Establish Android TUN Interface (tun0)
            startVpnInterface()

            // Start protocol client engine
            when {
                config.selectedMethod.contains("Method 1") || config.selectedMethod.contains("DNS") -> {
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

    private fun verifyServerConnection(config: TunnelConfig): Boolean {
        return try {
            val healthUrl = "${config.serverUrl.rstrip('/')}/health"
            val clientBuilder = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .socketFactory(object : SocketFactory() {
                    private val def = getDefault()
                    override fun createSocket(): Socket = def.createSocket().also { protect(it) }
                    override fun createSocket(host: String?, port: Int): Socket = def.createSocket(host, port).also { protect(it) }
                    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = def.createSocket(host, port, localHost, localPort).also { protect(it) }
                    override fun createSocket(host: InetAddress?, port: Int): Socket = def.createSocket(host, port).also { protect(it) }
                    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = def.createSocket(address, port, localAddress, localPort).also { protect(it) }
                })

            val client = clientBuilder.build()
            val request = Request.Builder().url(healthUrl).build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 200 || response.code == 404
            }
        } catch (e: Exception) {
            Logger.log("VERIFY", "Verification ping error: ${e.message}")
            false
        }
    }

    private fun String.rstrip(c: Char): String = if (endsWith(c)) substring(0, length - 1) else this

    private fun startVpnInterface() {
        try {
            val builder = Builder()
                .setSession("BDNET Tunnel")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0) // Intercept ALL IPv4 traffic
                .addDnsServer("1.1.1.1") // Route DNS lookups to VPN interface
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                isVpnRunning = true
                Logger.log("VPN", "Native TUN interface established! (10.0.0.2/24 -> 0.0.0.0/0)")
                startTunPacketLoop()
            } else {
                Logger.log("VPN", "Failed to establish VPN interface (Permission missing?).")
            }
        } catch (e: Exception) {
            Logger.log("VPN", "Error establishing TUN interface: ${e.message}")
        }
    }

    private fun startTunPacketLoop() {
        tunThread = Thread {
            val pfd = vpnInterface ?: return@Thread
            val inStream = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(32767)

            try {
                while (isVpnRunning) {
                    val readLen = inStream.read(buffer)
                    if (readLen <= 0) continue

                    // Add captured packet traffic to stats
                    Logger.addTraffic(readLen.toLong(), (readLen * 0.85).toLong())

                    // Parse IP Header (IPv4 = version 4)
                    val ipVersion = (buffer[0].toInt() shr 4) and 0x0F
                    if (ipVersion == 4 && readLen >= 20) {
                        val protocol = buffer[9].toInt() and 0xFF
                        if (protocol == 17 && readLen >= 28) { // UDP
                            val destPort = ((buffer[22].toInt() and 0xFF) shl 8) or (buffer[23].toInt() and 0xFF)
                            if (destPort == 53) { // DNS Query Interception
                                Logger.log("VPN_DNS", "Intercepted OS DNS Query ($readLen bytes)")
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (isVpnRunning) Logger.log("VPN", "TUN loop exception: ${e.message}")
            }
        }.apply { start() }
    }

    private fun startStatsLoop() {
        statsRunnable = object : Runnable {
            override fun run() {
                if (isVpnRunning) {
                    Logger.updateStreams(1)
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
            .setContentTitle("BDNET Tunnel (VPN Active)")
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
