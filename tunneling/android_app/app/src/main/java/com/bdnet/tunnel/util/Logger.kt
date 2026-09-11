package com.bdnet.tunnel.util

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    interface LogListener {
        fun onLogReceived(line: String)
        fun onStateChanged(isConnected: Boolean, statusText: String)
    }

    private val listeners = mutableListOf<LogListener>()
    private val logHistory = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    var isConnected: Boolean = false
        private set

    fun addListener(listener: LogListener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
        // Send history to newly attached listener
        handler.post {
            listener.onLogReceived(logHistory.toString())
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
        handler.post {
            synchronized(listeners) {
                for (listener in listeners) {
                    listener.onStateChanged(connected, statusText)
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
