package com.watchdog.moonlight

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MoonlightWatchdog"
        const val HOST_IP = "192.168.50.123"
        const val HOST_PORT = 47984
        const val PING_TIMEOUT_MS = 3000
        const val MOONLIGHT_PACKAGE = "com.limelight"
        const val MOONLIGHT_TRAMPOLINE = "com.limelight.ShortcutTrampoline"
        const val PC_UUID = "1B7B6FC7-D45C-082A-5087-54539D364B75"
        const val PC_NAME = "msc-o3-ghost"
        const val APP_ID = 881448767
        const val APP_NAME = "Desktop"
        const val PING_INTERVAL_MS = 4000L
        const val RECONNECT_DELAY_MS = 2000L
    }

    private lateinit var statusText: TextView
    private lateinit var wifiStatus: TextView
    private lateinit var moonlightStatus: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var isWifiAvailable = false
    private var isMoonlightRunning = false
    private var isReconnecting = false

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (!isMoonlightRunning) checkHostAndReconnect()
            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        wifiStatus = findViewById(R.id.wifiStatus)
        moonlightStatus = findViewById(R.id.moonlightStatus)
        setupNetworkMonitoring()
        handler.postDelayed(pingRunnable, PING_INTERVAL_MS)
        updateStatus("Initializing...")
    }

    override fun onResume() {
        super.onResume()
        if (isMoonlightRunning) {
            Log.d(TAG, "Moonlight returned — session ended")
            isMoonlightRunning = false
            isReconnecting = false
            updateStatus("Disconnected. Waiting to reconnect...")
        }
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isWifiAvailable = true
                runOnUiThread { updateStatus("WiFi connected. Checking host...") }
            }
            override fun onLost(network: Network) {
                isWifiAvailable = false
                runOnUiThread { updateStatus("WiFi lost. Waiting...") }
            }
        }
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback
        )
        isWifiAvailable = isNetworkAvailable()
    }

    private fun isNetworkAvailable(): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork ?: return false
        ) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun checkHostAndReconnect() {
        if (isMoonlightRunning || isReconnecting) return
        if (!isWifiAvailable) { updateStatus("Waiting for WiFi..."); return }
        Thread {
            val reachable = pingHost()
            runOnUiThread {
                if (reachable && !isMoonlightRunning && !isReconnecting) launchMoonlight()
                else if (!reachable) updateStatus("Host unreachable. Retrying...")
            }
        }.start()
    }

    private fun pingHost(): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(HOST_IP, HOST_PORT), PING_TIMEOUT_MS) }
            true
        } catch (e: Exception) { false }
    }

    private fun launchMoonlight() {
        isReconnecting = true
        updateStatus("Host reachable! Launching Moonlight...")
        handler.postDelayed({
            try {
                startActivity(Intent().apply {
                    setClassName(MOONLIGHT_PACKAGE, MOONLIGHT_TRAMPOLINE)
                    putExtra("UUID", PC_UUID)
                    putExtra("Name", PC_NAME)
                    putExtra("AppId", APP_ID.toString())  // must be String
                    putExtra("AppName", APP_NAME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                isMoonlightRunning = true
                isReconnecting = false
                updateStatus("Moonlight launched!")
            } catch (e: Exception) {
                isMoonlightRunning = false
                isReconnecting = false
                updateStatus("Launch failed: ${e.message}")
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun updateStatus(message: String) {
        statusText.text = message
        wifiStatus.text = if (isWifiAvailable) "WiFi: ✓" else "WiFi: ✗"
        moonlightStatus.text = if (isMoonlightRunning) "Moonlight: ✓ Running" else "Moonlight: ✗ Stopped"
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}
