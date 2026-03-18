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

class WatchdogService : Service() {

    companion object {
        const val TAG = "WatchdogService"
        const val HOST_IP = "192.168.50.123"
        const val HOST_PORT = 47984
        const val PING_TIMEOUT_MS = 3000
        const val MOONLIGHT_PACKAGE = "com.limelight"
        const val MOONLIGHT_TRAMPOLINE = "com.limelight.ShortcutTrampoline"
        const val PC_UUID = "1B7B6FC7-D45C-082A-5087-54539D364B75"
        const val PC_NAME = "msc-o3-ghost"
        const val APP_ID = 881448767
        const val APP_NAME = "Desktop"
        const val PING_INTERVAL_MS = 5000L
        const val CHANNEL_ID = "watchdog_channel"
        var isSessionActive = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var connectivityManager: ConnectivityManager
    private var isWifiAvailable = false
    private var isReconnecting = false

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (!isSessionActive && !isReconnecting) {
                checkAndReconnect()
            }
            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isWifiAvailable = true
        }
        override fun onLost(network: Network) {
            isWifiAvailable = false
            isSessionActive = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("Watchdog running..."))
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

    private fun checkAndReconnect() {
        if (!isWifiAvailable) return
        Thread {
            val reachable = pingHost()
            if (reachable) {
                handler.post { launchMoonlight() }
            }
        }.start()
    }

    private fun pingHost(): Boolean {
        return try {
            java.net.Socket().use {
                it.connect(java.net.InetSocketAddress(HOST_IP, HOST_PORT), PING_TIMEOUT_MS)
            }
            true
        } catch (e: Exception) { false }
    }

    private fun launchMoonlight() {
        isReconnecting = true
        // Kill Moonlight first to clear any stuck dialog
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            // bring our app to front first
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) { }

        handler.postDelayed({
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
                updateNotification("Moonlight session active")
            } catch (e: Exception) {
                isReconnecting = false
                updateNotification("Launch failed: ${e.message}")
            }
        }, 2000)
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
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
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

        // Start the foreground service
        startForegroundService(Intent(this, WatchdogService::class.java))
        findViewById<TextView>(R.id.statusText).text = "Watchdog service running in background"
    }

    override fun onResume() {
        super.onResume()
        // When Moonlight returns focus to us, mark session as inactive
        WatchdogService.isSessionActive = false
    }
}
