package com.bdnet.tunnel.tunnel

import com.bdnet.tunnel.model.TunnelConfig
import com.bdnet.tunnel.util.Logger
import okhttp3.*
import okio.ByteString
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

class VlessClient(private val config: TunnelConfig) {
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    fun start() {
        isRunning = true
        Logger.log("VLESS", "Connecting to VLESS Server: ${config.vlessUrl}")
        Logger.log("VLESS", "UUID: ${config.uuid}")

        val request = Request.Builder()
            .url(config.vlessUrl)
            .addHeader("Sec-WebSocket-Protocol", "vless")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.log("VLESS", "VLESS WebSocket Connection Established!")
                Logger.setConnectionState(true, "CONNECTED (VLESS over WS)")
                sendVlessHeader(webSocket)
                startLocalProxy()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Logger.log("VLESS", "Received ${bytes.size} bytes from VLESS tunnel")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.log("VLESS", "VLESS Connection Failed: ${t.message}")
                Logger.setConnectionState(false, "DISCONNECTED (VLESS Error)")
                stop()
            }
        })
    }

    private fun sendVlessHeader(ws: WebSocket) {
        val uuidBytes = getUuidBytes(config.uuid)
        val header = ByteArray(1 + 16 + 1 + 2 + 1 + 2)
        header[0] = 0x00 // Version 0
        System.arraycopy(uuidBytes, 0, header, 1, 16)
        header[17] = 0x00 // Addons length
        header[18] = 0x01 // Command CONNECT
        header[19] = 0x00 // Port high
        header[20] = 80.toByte() // Port low (80)
        header[21] = 0x01 // Address type IPv4
        ws.send(ByteString.of(*header))
    }

    private fun getUuidBytes(uuidStr: String): ByteArray {
        return try {
            val uuid = UUID.fromString(uuidStr)
            val bb = java.nio.ByteBuffer.allocate(16)
            bb.putLong(uuid.mostSignificantBits)
            bb.putLong(uuid.leastSignificantBits)
            bb.array()
        } catch (e: Exception) {
            ByteArray(16)
        }
    }

    private fun startLocalProxy() {
        Thread {
            try {
                serverSocket = ServerSocket(config.localPort)
                Logger.log("VLESS", "Local SOCKS5 proxy active on 127.0.0.1:${config.localPort}")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    // Forward traffic through VLESS WS
                    Thread {
                        val input = socket.getInputStream()
                        val buf = ByteArray(4096)
                        try {
                            while (isRunning) {
                                val read = input.read(buf)
                                if (read == -1) break
                                webSocket?.send(buf.toByteString(0, read))
                            }
                        } catch (e: Exception) {}
                        socket.close()
                    }.start()
                }
            } catch (e: Exception) {
                if (isRunning) Logger.log("VLESS", "Proxy error: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        webSocket?.close(1000, "Stopping")
        Logger.log("VLESS", "VLESS Client stopped.")
        Logger.setConnectionState(false, "DISCONNECTED")
    }
}
