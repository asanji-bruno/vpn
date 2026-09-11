package com.bdnet.tunnel.tunnel

import com.bdnet.tunnel.model.TunnelConfig
import com.bdnet.tunnel.util.Logger
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class HttpConnectClient(private val config: TunnelConfig) {
    private var isRunning = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        isRunning = true
        Logger.log("HTTP_PROXY", "Starting HTTP CONNECT Proxy (Method 6)...")
        Logger.log("HTTP_PROXY", "Target Server URL: ${config.serverUrl}")

        Thread {
            try {
                serverSocket = ServerSocket(config.localPort)
                Logger.log("HTTP_PROXY", "HTTP Proxy listening on 127.0.0.1:${config.localPort}")
                Logger.setConnectionState(true, "CONNECTED (HTTP Proxy)")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    Thread { handleConnection(clientSocket) }.start()
                }
            } catch (e: Exception) {
                if (isRunning) Logger.log("HTTP_PROXY", "Listener error: ${e.message}")
            }
        }.start()
    }

    private fun handleConnection(clientSocket: Socket) {
        try {
            val serverHost = config.serverUrl.replace("https://", "").replace("http://", "").split("/")[0]
            val upstreamSocket = Socket(serverHost, 80)

            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val upstreamIn = upstreamSocket.getInputStream()
            val upstreamOut = upstreamSocket.getOutputStream()

            // Send CONNECT request to upstream HTTP Proxy
            val connectReq = "CONNECT 127.0.0.1:80 HTTP/1.1\r\nHost: $serverHost\r\nX-BDNET-Auth: ${config.authToken}\r\n\r\n"
            upstreamOut.write(connectReq.toByteArray())
            upstreamOut.flush()

            val t1 = Thread { pipe(clientIn, upstreamOut) }
            val t2 = Thread { pipe(upstreamIn, clientOut) }
            t1.start(); t2.start()
            t1.join(); t2.join()

            clientSocket.close()
            upstreamSocket.close()
        } catch (e: Exception) {
            Logger.log("HTTP_PROXY", "Tunnel error: ${e.message}")
        }
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(8192)
        try {
            while (isRunning) {
                val read = input.read(buf)
                if (read == -1) break
                output.write(buf, 0, read)
                output.flush()
            }
        } catch (e: Exception) {}
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        Logger.log("HTTP_PROXY", "HTTP Proxy stopped.")
        Logger.setConnectionState(false, "DISCONNECTED")
    }
}
