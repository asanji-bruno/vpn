package com.bdnet.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bdnet.tunnel.model.TunnelConfig
import com.bdnet.tunnel.tunnel.*
import com.bdnet.tunnel.util.Logger

class TunnelService : Service() {
    private val binder = LocalBinder()
    private var wsClient: WsTunnelClient? = null
    private var vlessClient: VlessClient? = null
    private var dnsClient: DnsTunnelClient? = null
    private var sniClient: SniInjectClient? = null
    private var sshClient: SshWsClient? = null
    private var httpProxyClient: HttpConnectClient? = null

    inner class LocalBinder : Binder() {
        fun getService(): TunnelService = this@TunnelService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            startForeground(NOTIFICATION_ID, createNotification("Connecting BDNET Tunnel..."))
        } else if (action == ACTION_STOP) {
            stopTunnel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    fun startTunnel(config: TunnelConfig) {
        stopTunnel()
        Logger.log("SERVICE", "Starting Tunnel Engine for: ${config.selectedMethod}")
        startForeground(NOTIFICATION_ID, createNotification("Connected: ${config.selectedMethod}"))

        when {
            config.selectedMethod.contains("Method 1") || config.selectedMethod.contains("DNS") -> {
                dnsClient = DnsTunnelClient(config).also { it.start() }
            }
            config.selectedMethod.contains("Method 2") || config.selectedMethod.contains("WebSocket") -> {
                wsClient = WsTunnelClient(config).also { it.start() }
            }
            config.selectedMethod.contains("Method 3") || config.selectedMethod.contains("SSH") -> {
                sshClient = SshWsClient(config).also { it.start() }
            }
            config.selectedMethod.contains("Method 4") || config.selectedMethod.contains("SNI") -> {
                sniClient = SniInjectClient(config).also { it.start() }
            }
            config.selectedMethod.contains("Method 5") || config.selectedMethod.contains("VLESS") -> {
                vlessClient = VlessClient(config).also { it.start() }
            }
            config.selectedMethod.contains("Method 6") || config.selectedMethod.contains("HTTP") -> {
                httpProxyClient = HttpConnectClient(config).also { it.start() }
            }
            else -> {
                wsClient = WsTunnelClient(config).also { it.start() }
            }
        }
    }

    fun stopTunnel() {
        Logger.log("SERVICE", "Stopping all active tunnels...")
        wsClient?.stop(); wsClient = null
        vlessClient?.stop(); vlessClient = null
        dnsClient?.stop(); dnsClient = null
        sniClient?.stop(); sniClient = null
        sshClient?.stop(); sshClient = null
        httpProxyClient?.stop(); httpProxyClient = null
        Logger.setConnectionState(false, "DISCONNECTED")
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
        const val CHANNEL_ID = "BDNET_TUNNEL_CHANNEL"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.bdnet.tunnel.START"
        const val ACTION_STOP = "com.bdnet.tunnel.STOP"
    }
}
