package com.watchdog.moonlight

import android.app.*
import android.content.*
import android.net.*
import android.os.*
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import java.net.InetSocketAddress
import java.net.Socket

class WatchdogService : Service() {

    companion object {
        const val TAG = "WatchdogService"
        const val HOST_IP = "192.168.50.123"
        const val HOST_PORT = 47984          // Sunshine control port
        const val STREAM_PORT = 48010        // Active stream port
        const val PING_TIMEOUT_MS = 2000
        const val MOONLIGHT_PACKAGE = "com.limelight"
        const val MOONLIGHT_TRAMPOLINE = "com.limelight.ShortcutTrampoline"
        const val PC_UUID = "1B7B6FC7-D45C-082A-5087-54539D364B75"
        const val PC_NAME = "msc-o3-ghost"
        const val APP_ID = 881448767
        const val APP_NAME = "Desktop"
        const val CHANNEL_ID = "watchdog_channel"
        const val PING_INTERVAL_MS = 3000L

        // States
        var isSessionActive = false
        var isReconnecting = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var connectivityManager: ConnectivityManager
    private var isWifiAvailable = false

    private val pingRunnable = object : Runnable {
        override fun run() {
            checkSession()
            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isWifiAvailable = true
        }
        override fun onLost(network: Network) {
            isWifiAvailable = false
            if (isSessionActive) {
                // WiFi lost during session
                onSessionTerminated()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("Watchdog active"))
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback
        )
        isWifiAvailable = isNetworkAvailable()
        handler.postDelayed(pingRunnable, PING_INTERVAL_MS)
    }

    private fun checkSession() {
        Thread {
            if (isSessionActive) {
                // Session should be active — check streaming port
                val streamAlive = pingPort(STREAM_PORT)
                if (!streamAlive) {
                    Log.d(TAG, "Stream port dead — session terminated")
                    handler.post { onSessionTerminated() }
                }
            } else if (!isReconnecting) {
                // No session — check if Sunshine is reachable to reconnect
                if (!isWifiAvailable) return@Thread
                val hostReachable = pingPort(HOST_PORT)
                if (hostReachable) {
                    Log.d(TAG, "Host reachable — launching reconnect")
                    handler.post { showReconnectScreen() }
                } else {
                    updateNotification("Waiting for host...")
                }
            }
        }.start()
    }

    private fun onSessionTerminated() {
        Log.d(TAG, "Session terminated — killing Moonlight")
        isSessionActive = false
        isReconnecting = false

        // Force stop Moonlight to clear its dialog
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(MOONLIGHT_PACKAGE)
        } catch (e: Exception) {
            Log.e(TAG, "Could not kill Moonlight: ${e.message}")
        }

        // Show reconnect splash immediately
        showReconnectScreen()
    }

    fun showReconnectScreen() {
        isReconnecting = true
        updateNotification("Reconnecting...")
        try {
            startActivity(Intent(this, ReconnectActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            })
        } catch (e: Exception) {
            isReconnecting = false
            Log.e(TAG, "Failed to show reconnect screen: ${e.message}")
        }
    }

    fun launchMoonlight() {
        try {
            startActivity(Intent().apply {
                setClassName(MOONLIGHT_PACKAGE, MOONLIGHT_TRAMPOLINE)
                putExtra("UUID", PC_UUID)
                putExtra("Name", PC_NAME)
                putExtra("AppId", APP_ID.toString())
                putExtra("AppName", APP_NAME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            isSessionActive = true
            isReconnecting = false
            updateNotification("Session active")
        } catch (e: Exception) {
            isSessionActive = false
            isReconnecting = false
            Log.e(TAG, "Failed to launch Moonlight: ${e.message}")
            updateNotification("Launch failed")
        }
    }

    private fun pingPort(port: Int): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(HOST_IP, port), PING_TIMEOUT_MS) }
            true
        } catch (e: Exception) { false }
    }

    private fun isNetworkAvailable(): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork ?: return false
        ) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Moonlight Watchdog")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Watchdog", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        if (!android.provider.Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } else {
            startForegroundService(Intent(this, WatchdogService::class.java))
        }

        findViewById<TextView>(R.id.statusText).text = "Watchdog running..."
    }

    override fun onResume() {
        super.onResume()
        if (android.provider.Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, WatchdogService::class.java))
        }
        WatchdogService.isSessionActive = false
        WatchdogService.isReconnecting = false
    }
}