package com.bdnet.tunnel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bdnet.tunnel.model.TunnelConfig
import com.bdnet.tunnel.util.Logger
import com.google.android.material.button.MaterialButton
import java.util.Locale

class MainActivity : AppCompatActivity(), Logger.LogListener {

    private lateinit var spMethod: Spinner
    private lateinit var etServerUrl: EditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvVerifyDetails: TextView

    // Tab Layout Views
    private lateinit var layoutHome: LinearLayout
    private lateinit var layoutStats: LinearLayout
    private lateinit var layoutTerminal: LinearLayout

    private lateinit var btnTabHome: MaterialButton
    private lateinit var btnTabStats: MaterialButton
    private lateinit var btnTabTerminal: MaterialButton

    // Data Monitor Views
    private lateinit var tvDownloadSpeed: TextView
    private lateinit var tvUploadSpeed: TextView
    private lateinit var tvTotalDownload: TextView
    private lateinit var tvTotalUpload: TextView
    private lateinit var tvActiveStreams: TextView
    private lateinit var tvSessionDuration: TextView
    private lateinit var tvActiveProtocol: TextView

    // Terminal Log Views
    private lateinit var tvTerminalLog: TextView
    private lateinit var svTerminal: ScrollView

    private var tunnelService: TunnelService? = null
    private var isBound = false
    private val config = TunnelConfig()

    private var lastTxBytes: Long = 0
    private var lastRxBytes: Long = 0
    private var lastStatsTime: Long = 0

    private val methods = arrayOf(
        "Method 2: WebSocket Relay (WSS)",
        "Method 5: VLESS over WebSocket",
        "Method 3: SSH over WebSocket Bridge",
        "Method 1: DNS Tunneling (Base32 UDP)",
        "Method 4: SNI Payload Injector",
        "Method 6: HTTP CONNECT Proxy"
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TunnelService.LocalBinder
            tunnelService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            tunnelService = null
            isBound = false
        }
    }

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            launchTunnelService()
        } else {
            Logger.log("VPN", "VPN permission was denied by user.")
            tvVerifyDetails.text = "Error: VPN Permission Denied by User."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spMethod = findViewById(R.id.spMethod)
        etServerUrl = findViewById(R.id.etServerUrl)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvVerifyDetails = findViewById(R.id.tvVerifyDetails)

        layoutHome = findViewById(R.id.layoutHome)
        layoutStats = findViewById(R.id.layoutStats)
        layoutTerminal = findViewById(R.id.layoutTerminal)

        btnTabHome = findViewById(R.id.btnTabHome)
        btnTabStats = findViewById(R.id.btnTabStats)
        btnTabTerminal = findViewById(R.id.btnTabTerminal)

        tvDownloadSpeed = findViewById(R.id.tvDownloadSpeed)
        tvUploadSpeed = findViewById(R.id.tvUploadSpeed)
        tvTotalDownload = findViewById(R.id.tvTotalDownload)
        tvTotalUpload = findViewById(R.id.tvTotalUpload)
        tvActiveStreams = findViewById(R.id.tvActiveStreams)
        tvSessionDuration = findViewById(R.id.tvSessionDuration)
        tvActiveProtocol = findViewById(R.id.tvActiveProtocol)

        tvTerminalLog = findViewById(R.id.tvTerminalLog)
        svTerminal = findViewById(R.id.svTerminal)
        val btnClearLogs = findViewById<TextView>(R.id.btnClearLogs)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, methods)
        spMethod.adapter = adapter
        etServerUrl.setText(config.serverUrl)

        // Setup Tab Navigation Listener
        btnTabHome.setOnClickListener { switchTab(0) }
        btnTabStats.setOnClickListener { switchTab(1) }
        btnTabTerminal.setOnClickListener { switchTab(2) }

        btnConnect.setOnClickListener {
            if (Logger.isConnected) {
                stopTunnelService()
            } else {
                checkVpnAndStart()
            }
        }

        btnClearLogs.setOnClickListener {
            Logger.clearLogs()
        }

        Logger.addListener(this)

        // Bind foreground VPN service
        val intent = Intent(this, TunnelService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun switchTab(tabIndex: Int) {
        layoutHome.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        layoutStats.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        layoutTerminal.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE

        btnTabHome.setTextColor(if (tabIndex == 0) Color.parseColor("#06B6D4") else Color.parseColor("#94A3B8"))
        btnTabStats.setTextColor(if (tabIndex == 1) Color.parseColor("#06B6D4") else Color.parseColor("#94A3B8"))
        btnTabTerminal.setTextColor(if (tabIndex == 2) Color.parseColor("#06B6D4") else Color.parseColor("#94A3B8"))
    }

    private fun checkVpnAndStart() {
        tvVerifyDetails.text = "Initiating server reachability check & VPN permission..."
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnLauncher.launch(vpnIntent)
        } else {
            launchTunnelService()
        }
    }

    private fun launchTunnelService() {
        var rawUrl = etServerUrl.text.toString().trim()
        if (rawUrl.isEmpty()) {
            tvVerifyDetails.text = "Please enter a valid server URL."
            return
        }
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            rawUrl = "https://$rawUrl"
        }
        rawUrl = rawUrl.trimEnd('/')
        config.serverUrl = rawUrl

        val isSecure = rawUrl.startsWith("https://")
        val wsScheme = if (isSecure) "wss://" else "ws://"
        val hostAndPort = rawUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')

        config.wsUrl = "$wsScheme$hostAndPort/ws"
        config.vlessUrl = "$wsScheme$hostAndPort/vless"
        config.sshWsUrl = "$wsScheme$hostAndPort/ssh-relay"
        config.selectedMethod = spMethod.selectedItem.toString()

        Logger.log("INIT", "Target Server: ${config.serverUrl}")
        Logger.log("INIT", "Target WebSocket: ${config.wsUrl}")

        val serviceIntent = Intent(this, TunnelService::class.java).apply {
            action = TunnelService.ACTION_START
            putExtra("selectedMethod", config.selectedMethod)
            putExtra("serverUrl", config.serverUrl)
            putExtra("wsUrl", config.wsUrl)
            putExtra("vlessUrl", config.vlessUrl)
            putExtra("sshWsUrl", config.sshWsUrl)
        }
        startService(serviceIntent)
    }

    private fun stopTunnelService() {
        tunnelService?.stopTunnel()
        val serviceIntent = Intent(this, TunnelService::class.java).apply {
            action = TunnelService.ACTION_STOP
        }
        startService(serviceIntent)
        tvVerifyDetails.text = "Tunnel stopped by user."
    }

    override fun onLogReceived(line: String) {
        runOnUiThread {
            if (line.isEmpty()) {
                tvTerminalLog.text = ""
            } else {
                tvTerminalLog.append(line)
                svTerminal.post { svTerminal.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    override fun onStateChanged(isConnected: Boolean, statusText: String) {
        runOnUiThread {
            tvActiveProtocol.text = "Active Protocol: ${spMethod.selectedItem}"
            if (isConnected) {
                btnConnect.text = "STOP TUNNEL"
                btnConnect.setBackgroundColor(Color.parseColor("#EF4444"))
                tvStatusBadge.text = "CONNECTED"
                tvStatusBadge.setTextColor(Color.parseColor("#10B981"))
                tvStatusBadge.setBackgroundColor(Color.parseColor("#3310B981"))
                tvVerifyDetails.text = "CONNECTED: Server handshake verified. All OS traffic routed via ${spMethod.selectedItem}."
            } else {
                btnConnect.text = "START TUNNEL"
                btnConnect.setBackgroundColor(Color.parseColor("#06B6D4"))
                tvStatusBadge.text = if (statusText.contains("FAILED")) "FAILED" else "DISCONNECTED"
                tvStatusBadge.setTextColor(Color.parseColor("#EF4444"))
                tvStatusBadge.setBackgroundColor(Color.parseColor("#33EF4444"))
                if (statusText.contains("VERIFICATION FAILED")) {
                    tvVerifyDetails.text = "SERVER VERIFICATION FAILED: Unable to communicate with server at ${etServerUrl.text}. Please check endpoint URL or server status."
                }
            }
        }
    }

    override fun onStatsUpdated(txBytes: Long, rxBytes: Long, activeStreams: Int, durationSec: Long) {
        runOnUiThread {
            val now = System.currentTimeMillis()
            val timeDeltaSec = if (lastStatsTime > 0) (now - lastStatsTime) / 1000.0 else 1.0

            val txSpeed = if (timeDeltaSec > 0) (txBytes - lastTxBytes) / timeDeltaSec else 0.0
            val rxSpeed = if (timeDeltaSec > 0) (rxBytes - lastRxBytes) / timeDeltaSec else 0.0

            lastTxBytes = txBytes
            lastRxBytes = rxBytes
            lastStatsTime = now

            tvDownloadSpeed.text = formatBytesSpeed(rxSpeed.toLong())
            tvUploadSpeed.text = formatBytesSpeed(txSpeed.toLong())

            tvTotalDownload.text = "Total: ${formatBytes(rxBytes)}"
            tvTotalUpload.text = "Total: ${formatBytes(txBytes)}"

            tvActiveStreams.text = "Active Stream Connections: $activeStreams"
            tvSessionDuration.text = "Tunnel Duration: ${formatSeconds(durationSec)}"
        }
    }

    private fun formatBytesSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_000_000.0)
            bytesPerSec >= 1_000 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1000.0)
            else -> "$bytesPerSec B/s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1000.0)
            else -> "$bytes B"
        }
    }

    private fun formatSeconds(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.removeListener(this)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
