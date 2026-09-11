package com.bdnet.tunnel.util

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object Logger {
    interface LogListener {
        fun onLogReceived(line: String)
        fun onStateChanged(isConnected: Boolean, statusText: String)
        fun onStatsUpdated(txBytes: Long, rxBytes: Long, activeStreams: Int, durationSec: Long) {}
    }

    private val listeners = mutableListOf<LogListener>()
    private val logHistory = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    
    var isConnected: Boolean = false
        private set

    val totalTxBytes = AtomicLong(0)
    val totalRxBytes = AtomicLong(0)
    val activeStreams = AtomicInteger(0)
    private var sessionStartTime: Long = 0

    fun addListener(listener: LogListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
        handler.post {
            listener.onLogReceived(logHistory.toString())
            listener.onStateChanged(isConnected, if (isConnected) "CONNECTED" else "DISCONNECTED")
            listener.onStatsUpdated(totalTxBytes.get(), totalRxBytes.get(), activeStreams.get(), getDurationSec())
        }
    }

    fun removeListener(listener: LogListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun log(tag: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formatted = "[$timestamp][$tag] $message\n"
        
        synchronized(logHistory) {
            logHistory.append(formatted)
        }

        handler.post {
            synchronized(listeners) {
                for (listener in listeners) {
                    listener.onLogReceived(formatted)
                }
            }
        }
    }

    fun setConnectionState(connected: Boolean, statusText: String) {
        isConnected = connected
        if (connected) {
            if (sessionStartTime == 0L) sessionStartTime = System.currentTimeMillis()
        } else {
            sessionStartTime = 0L
        }
        handler.post {
            synchronized(listeners) {
                for (listener in listeners) {
                    listener.onStateChanged(connected, statusText)
                }
            }
        }
    }

    fun addTraffic(tx: Long, rx: Long) {
        totalTxBytes.addAndGet(tx)
        totalRxBytes.addAndGet(rx)
        notifyStats()
    }

    fun updateStreams(count: Int) {
        activeStreams.set(count)
        notifyStats()
    }

    fun resetStats() {
        totalTxBytes.set(0)
        totalRxBytes.set(0)
        activeStreams.set(0)
        sessionStartTime = 0L
        notifyStats()
    }

    private fun getDurationSec(): Long {
        return if (sessionStartTime > 0L) (System.currentTimeMillis() - sessionStartTime) / 1000 else 0L
    }

    private fun notifyStats() {
        val tx = totalTxBytes.get()
        val rx = totalRxBytes.get()
        val streams = activeStreams.get()
        val dur = getDurationSec()

        handler.post {
            synchronized(listeners) {
                for (listener in listeners) {
                    listener.onStatsUpdated(tx, rx, streams, dur)
                }
            }
        }
    }

    fun clearLogs() {
        synchronized(logHistory) {
            logHistory.setLength(0)
        }
        handler.post {
            synchronized(listeners) {
                for (listener in listeners) {
                    listener.onLogReceived("")
                }
            }
        }
    }

    fun getLogs(): String = logHistory.toString()
}
