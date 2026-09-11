package com.bdnet.tunnel.tunnel

import com.bdnet.tunnel.model.TunnelConfig
import com.bdnet.tunnel.util.Logger
import okhttp3.*
import okio.ByteString
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WsTunnelClient(private val config: TunnelConfig) {
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val activeStreams = ConcurrentHashMap<Int, StreamPair>()
    private val nextStreamId = AtomicInteger(1)

    data class StreamPair(val socket: Socket, val input: InputStream, val output: OutputStream)

    fun start() {
        isRunning = true
        Logger.log("WS_RELAY", "Starting WebSocket Relay Client...")
        Logger.log("WS_RELAY", "Target Server: ${config.wsUrl}")

        val request = Request.Builder()
            .url(config.wsUrl)
            .addHeader("X-BDNET-Auth", config.authToken)
            .addHeader("X-BDNET-UUID", config.uuid)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logger.log("WS_RELAY", "WebSocket connection ESTABLISHED! Status: ${response.code}")
                Logger.setConnectionState(true, "CONNECTED (WebSocket Relay)")
                startLocalListener()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerFrame(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logger.log("WS_RELAY", "WebSocket ERROR: ${t.message}")
                Logger.setConnectionState(false, "DISCONNECTED (Error: ${t.message})")
                stop()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Logger.log("WS_RELAY", "WebSocket closed: $reason ($code)")
                Logger.setConnectionState(false, "DISCONNECTED")
            }
        })
    }

    private fun startLocalListener() {
        Thread {
            try {
                serverSocket = ServerSocket(config.localPort)
                Logger.log("WS_RELAY", "Local SOCKS/HTTP Proxy listening on 127.0.0.1:${config.localPort}")
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    val streamId = nextStreamId.getAndIncrement()
                    val pair = StreamPair(clientSocket, clientSocket.getInputStream(), clientSocket.getOutputStream())
                    activeStreams[streamId] = pair

                    // Send CONNECT frame to WS server (stream_id, type 0x01)
                    val frame = ByteBuffer.allocate(9)
                        .putInt(streamId)
                        .put(0x01.toByte())
                        .putInt(0)
                        .array()
                    webSocket?.send(ByteString.of(*frame))

                    // Read from local socket and forward to WS
                    Thread {
                        val buffer = ByteArray(8192)
                        try {
                            while (isRunning) {
                                val bytesRead = pair.input.read(buffer)
                                if (bytesRead == -1) break
                                
                                val sendFrame = ByteBuffer.allocate(9 + bytesRead)
                                    .putInt(streamId)
                                    .put(0x00.toByte()) // DATA type
                                    .putInt(bytesRead)
                                    .put(buffer, 0, bytesRead)
                                    .array()
                                webSocket?.send(ByteString.of(*sendFrame))
                            }
                        } catch (e: Exception) {
                            // Stream closed
                        } finally {
                            closeStream(streamId)
                        }
                    }.start()
                }
            } catch (e: Exception) {
                if (isRunning) Logger.log("WS_RELAY", "Local listener error: ${e.message}")
            }
        }.start()
    }

    private fun handleServerFrame(data: ByteArray) {
        if (data.size < 9) return
        val buffer = ByteBuffer.wrap(data)
        val streamId = buffer.int
        val msgType = buffer.get()
        val dataLen = buffer.int

        val stream = activeStreams[streamId] ?: return

        when (msgType.toInt()) {
            0x00 -> { // DATA
                if (data.size >= 9 + dataLen) {
                    try {
                        stream.output.write(data, 9, dataLen)
                        stream.output.flush()
                    } catch (e: Exception) {
                        closeStream(streamId)
                    }
                }
            }
            0x02 -> { // DISCONNECT
                closeStream(streamId)
            }
        }
    }

    private fun closeStream(streamId: Int) {
        val stream = activeStreams.remove(streamId)
        try {
            stream?.socket?.close()
        } catch (e: Exception) {}
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        webSocket?.close(1000, "Client Stopping")
        activeStreams.clear()
        Logger.log("WS_RELAY", "WebSocket Relay stopped.")
        Logger.setConnectionState(false, "DISCONNECTED")
    }
}
