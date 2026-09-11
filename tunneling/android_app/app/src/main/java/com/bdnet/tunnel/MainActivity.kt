package com.bdnet.tunnel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bdnet.tunnel.model.TunnelConfig
import com.bdnet.tunnel.util.Logger
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity(), Logger.LogListener {

    private lateinit var spMethod: Spinner
    private lateinit var etServerUrl: EditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvTerminalLog: TextView
    private lateinit var svTerminal: ScrollView

    private var tunnelService: TunnelService? = null
    private var isBound = false
    private val config = TunnelConfig()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spMethod = findViewById(R.id.spMethod)
        etServerUrl = findViewById(R.id.etServerUrl)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvTerminalLog = findViewById(R.id.tvTerminalLog)
        svTerminal = findViewById(R.id.svTerminal)
        val btnClearLogs = findViewById<TextView>(R.id.btnClearLogs)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, methods)
        spMethod.adapter = adapter

        etServerUrl.setText(config.serverUrl)

        btnConnect.setOnClickListener {
            if (Logger.isConnected) {
                stopTunnelService()
            } else {
                startTunnelService()
            }
        }

        btnClearLogs.setOnClickListener {
            Logger.clearLogs()
        }

        Logger.addListener(this)

        // Bind foreground service
        val intent = Intent(this, TunnelService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startTunnelService() {
        config.serverUrl = etServerUrl.text.toString().trim()
        val ngrokBase = config.serverUrl.replace("https://", "").replace("http://", "")
        config.wsUrl = "wss://$ngrokBase/ws"
        config.vlessUrl = "wss://$ngrokBase/vless"
        config.sshWsUrl = "wss://$ngrokBase/ssh-relay"
        config.selectedMethod = spMethod.selectedItem.toString()

        val serviceIntent = Intent(this, TunnelService::class.java).apply {
            action = TunnelService.ACTION_START
        }
        startService(serviceIntent)
        tunnelService?.startTunnel(config)
    }

    private fun stopTunnelService() {
        tunnelService?.stopTunnel()
        val serviceIntent = Intent(this, TunnelService::class.java).apply {
            action = TunnelService.ACTION_STOP
        }
        startService(serviceIntent)
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
            if (isConnected) {
                btnConnect.text = "STOP TUNNEL"
                btnConnect.setBackgroundColor(Color.parseColor("#EF4444"))
                tvStatusBadge.text = "CONNECTED"
                tvStatusBadge.setTextColor(Color.parseColor("#10B981"))
                tvStatusBadge.setBackgroundColor(Color.parseColor("#3310B981"))
            } else {
                btnConnect.text = "START TUNNEL"
                btnConnect.setBackgroundColor(Color.parseColor("#06B6D4"))
                tvStatusBadge.text = "DISCONNECTED"
                tvStatusBadge.setTextColor(Color.parseColor("#EF4444"))
                tvStatusBadge.setBackgroundColor(Color.parseColor("#33EF4444"))
            }
        }
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
