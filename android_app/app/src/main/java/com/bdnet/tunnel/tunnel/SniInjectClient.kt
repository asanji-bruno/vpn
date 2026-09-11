package com.bdnet.tunnel.tunnel

import android.net.VpnService
import com.bdnet.tunnel.model.TunnelConfig
import com.bdnet.tunnel.util.Logger
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class SniInjectClient(private val config: TunnelConfig) {
    private var isRunning = false
    private var serverSocket: ServerSocket? = null
    private var activeVpnService: VpnService? = null

    fun start(vpnService: VpnService? = null) {
        isRunning = true
        activeVpnService = vpnService
        Logger.log("SNI_INJECT", "Starting SNI Payload Injector...")
        Logger.log("SNI_INJECT", "Bug Host / SNI: ${config.sniHost}")
        Logger.log("SNI_INJECT", "Payload Template: ${config.payload}")

        Thread {
            try {
                serverSocket = ServerSocket(config.localPort)
                Logger.log("SNI_INJECT", "Local proxy bound to 127.0.0.1:${config.localPort}")
                Logger.setConnectionState(true, "CONNECTED (SNI Injector)")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    Thread {
                        handleClient(clientSocket)
                    }.start()
                }
            } catch (e: Exception) {
                if (isRunning) Logger.log("SNI_INJECT", "Proxy Error: ${e.message}")
            }
        }.start()
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            // Perform TLS handshake with custom SNI bug host
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = factory.createSocket(config.sniHost, 443) as SSLSocket
            activeVpnService?.protect(sslSocket)

            val params = SSLParameters()
            params.serverNames = listOf(SNIHostName(config.sniHost))
            sslSocket.sslParameters = params
            sslSocket.startHandshake()

            Logger.log("SNI_INJECT", "TLS Handshake OK with ${config.sniHost}")

            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val sslIn = sslSocket.getInputStream()
            val sslOut = sslSocket.getOutputStream()

            // Inject custom HTTP payload with bug host header
            val injectedPayload = config.payload
                .replace("[host]", config.sniHost)
                .replace("[crlf]", "\r\n")

            sslOut.write(injectedPayload.toByteArray())
            sslOut.flush()

            // Bidirectional pipe
            val t1 = Thread { pipe(clientIn, sslOut) }
            val t2 = Thread { pipe(sslIn, clientOut) }
            t1.start(); t2.start()
            t1.join(); t2.join()

            clientSocket.close()
            sslSocket.close()
        } catch (e: Exception) {
            Logger.log("SNI_INJECT", "Connection fail: ${e.message}")
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
        Logger.log("SNI_INJECT", "SNI Injector stopped.")
        Logger.setConnectionState(false, "DISCONNECTED")
    }
}
