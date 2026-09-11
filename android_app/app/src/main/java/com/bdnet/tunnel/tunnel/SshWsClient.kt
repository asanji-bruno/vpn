package com.bdnet.tunnel.tunnel

import android.net.VpnService
import com.bdnet.tunnel.model.TunnelConfig
import com.bdnet.tunnel.util.Logger
import okhttp3.*
import okio.ByteString
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class SshWsClient(private val config: TunnelConfig) {
    private var isRunning = false
    private var webSocket: WebSocket? = null
    private var serverSocket: ServerSocket? = null

    fun start(vpnService: VpnService? = null) {
        isRunning = true
        Logger.log("SSH_WS", "Connecting SSH Bridge: ${config.sshWsUrl}")

        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)

        if (vpnService != null) {
            builder.socketFactory(object : SocketFactory() {
                private val defaultFactory = getDefault()
                override fun createSocket(): Socket = defaultFactory.createSocket().also { vpnService.protect(it) }
                override fun createSocket(host: String?, port: Int): Socket = defaultFactory.createSocket(host, port).also { vpnService.protect(it) }
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = defaultFactory.createSocket(host, port, localHost, localPort).also { vpnService.protect(it) }
                override fun createSocket(host: InetAddress?, port: Int): Socket = defaultFactory.createSocket(host, port).also { vpnService.protect(it) }
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = defaultFactory.createSocket(address, port, localAddress, localPort).also { vpnService.protect(it) }
            })
        }

        val okHttpClient = builder.build()

        val request = Request.Builder()
            .url(config.sshWsUrl)
            .addHeader("X-BDNET-Auth", config.authToken)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.log("SSH_WS", "SSH WebSocket Tunnel Connected!")
                Logger.setConnectionState(true, "CONNECTED (SSH over WS)")
                startLocalPortForward()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Logger.log("SSH_WS", "Received SSH Frame (${bytes.size} bytes)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.log("SSH_WS", "SSH WS Error: ${t.message}")
                Logger.setConnectionState(false, "DISCONNECTED (SSH Error)")
                stop()
            }
        })
    }

    private fun startLocalPortForward() {
        Thread {
            try {
                serverSocket = ServerSocket(config.localPort)
                Logger.log("SSH_WS", "Local SSH Port forwarding on 127.0.0.1:${config.localPort}")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Thread {
                        val input = socket.getInputStream()
                        val buf = ByteArray(4096)
                        try {
                            while (isRunning) {
                                val read = input.read(buf)
                                if (read == -1) break
                                webSocket?.send(ByteString.of(*buf.copyOfRange(0, read)))
                            }
                        } catch (e: Exception) {}
                        socket.close()
                    }.start()
                }
            } catch (e: Exception) {
                if (isRunning) Logger.log("SSH_WS", "Port forward error: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        webSocket?.close(1000, "Stopping")
        Logger.log("SSH_WS", "SSH WS Client stopped.")
        Logger.setConnectionState(false, "DISCONNECTED")
    }
}
