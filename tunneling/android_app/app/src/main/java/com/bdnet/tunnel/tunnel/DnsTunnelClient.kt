package com.bdnet.tunnel.tunnel

import com.bdnet.tunnel.model.TunnelConfig
import com.bdnet.tunnel.util.Logger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class DnsTunnelClient(private val config: TunnelConfig) {
    private var isRunning = false
    private var socket: DatagramSocket? = null
    private val seqNumber = AtomicInteger(1)

    fun start() {
        isRunning = true
        Logger.log("DNS_TUNNEL", "Starting DNS Tunnel (Method 1)...")
        Logger.log("DNS_TUNNEL", "DNS Server: ${config.dnsServer}, Domain: ${config.dnsDomain}")

        Thread {
            try {
                socket = DatagramSocket()
                Logger.setConnectionState(true, "CONNECTED (DNS Tunnel)")
                
                // Send keepalive query
                sendDnsData("PING")
            } catch (e: Exception) {
                Logger.log("DNS_TUNNEL", "DNS Error: ${e.message}")
                Logger.setConnectionState(false, "DISCONNECTED")
            }
        }.start()
    }

    private fun sendDnsData(dataStr: String) {
        try {
            val b32 = encodeBase32(dataStr.toByteArray())
            val domainQuery = "s${seqNumber.getAndIncrement()}-$b32.${config.dnsDomain}"
            Logger.log("DNS_TUNNEL", "Sending Query: $domainQuery")

            val dnsQueryBytes = buildDnsQuery(domainQuery)
            val packet = DatagramPacket(
                dnsQueryBytes,
                dnsQueryBytes.size,
                InetAddress.getByName(config.dnsServer),
                53
            )
            socket?.send(packet)
        } catch (e: Exception) {
            Logger.log("DNS_TUNNEL", "Send error: ${e.message}")
        }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        buf.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (part in domain.split(".")) {
            val bytes = part.toByteArray()
            buf.write(bytes.size)
            buf.write(bytes)
        }
        buf.write(0x00) // end label
        buf.write(byteArrayOf(0x00, 0x10, 0x00, 0x01)) // TXT Record class IN
        return buf.toByteArray()
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val sb = StringBuilder()
        var i = 0
        var index = 0
        var digit = 0
        var currByte: Int
        var nextByte: Int

        while (i < bytes.size) {
            currByte = if (bytes[i] < 0) bytes[i] + 256 else bytes[i].toInt()
            if (index > 3) {
                if (i + 1 < bytes.size) {
                    nextByte = if (bytes[i + 1] < 0) bytes[i + 1] + 256 else bytes[i + 1].toInt()
                } else {
                    nextByte = 0
                }
                digit = currByte and (0xFF shr index)
                index = (index + 5) % 8
                digit = digit shl index
                digit = digit or (nextByte shr (8 - index))
                i++
            } else {
                digit = (currByte shr (8 - (index + 5))) and 0x1F
                index = (index + 5) % 8
                if (index == 0) i++
            }
            sb.append(alphabet[digit])
        }
        return sb.toString()
    }

    fun stop() {
        isRunning = false
        try { socket?.close() } catch (e: Exception) {}
        Logger.log("DNS_TUNNEL", "DNS Tunnel stopped.")
        Logger.setConnectionState(false, "DISCONNECTED")
    }
}
