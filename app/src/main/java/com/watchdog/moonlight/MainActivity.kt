package com.watchdog.moonlight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.watchdog.moonlight.databinding.ActivityMainBinding
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MoonlightWatchdog"

        // Sunshine host
        const val HOST_IP = "192.168.50.123"
        const val HOST_PORT = 47984  // Sunshine control port
        const val PING_TIMEOUT_MS = 3000

        // Moonlight intent values
        const val MOONLIGHT_PACKAGE = "com.limelight"
        const val MOONLIGHT_TRAMPOLINE = "com.limelight.ShortcutTrampoline"
        const val PC_UUID = "1B7B6FC7-D45C-082A-5087-54539D364B75"
        const val PC_NAME = "msc-o3-ghost"
        const val APP_ID = 881448767
        const val APP_NAME = "Desktop"

        // Timing
        const val PING_INTERVAL_MS = 4000L
        const val RECONNECT_DELAY_MS = 2000L
    }

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    // State
    private var isWifiAvailable = false
    private var isMoonlightRunning = false
    private var isReconnecting = false

    // Network callback
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    // Ping runnable
    private val pingRunnable = object : Runnable {
        override fun run() {
            if (!isMoonlightRunning) {
                checkHostAndReconnect()
            }
            handler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNetworkMonitoring()
        startPingLoop()
        updateStatus("Initializing...")
    }

    override fun onResume() {
        super.onResume()
        // Moonlight returned to us = session ended
        if (isMoonlightRunning) {
            Log.d(TAG, "Moonlight returned to watchdog — session ended")
            isMoonlightRunning = false
            updateStatus("Disconnected. Waiting to reconnect...")
            isReconnecting = false
        }
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                isWifiAvailable = true
                runOnUiThread { updateStatus("WiFi connected. Checking host...") }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                isWifiAvailable = false
                runOnUiThread { updateStatus("WiFi lost. Waiting for connection...") }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback)

        // Check current state
        isWifiAvailable = isNetworkAvailable()
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startPingLoop() {
        handler.postDelayed(pingRunnable, PING_INTERVAL_MS)
    }

    private fun checkHostAndReconnect() {
        if (isMoonlightRunning || isReconnecting) return
        if (!isWifiAvailable) {
            updateStatus("Waiting for WiFi...")
            return
        }

        Thread {
            val reachable = pingHost()
            runOnUiThread {
                if (reachable) {
                    if (!isMoonlightRunning && !isReconnecting) {
                        launchMoonlight()
                    }
                } else {
                    updateStatus("Host unreachable. Retrying in ${PING_INTERVAL_MS / 1000}s...")
                }
            }
        }.start()
    }

    private fun pingHost(): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(HOST_IP, HOST_PORT), PING_TIMEOUT_MS)
            socket.close()
            Log.d(TAG, "Host reachable")
            true
        } catch (e: Exception) {
            Log.d(TAG, "Host unreachable: ${e.message}")
            false
        }
    }

    private fun launchMoonlight() {
        isReconnecting = true
        updateStatus("Host reachable! Launching Moonlight in ${RECONNECT_DELAY_MS / 1000}s...")

        handler.postDelayed({
            try {
                val intent = Intent().apply {
                    setClassName(MOONLIGHT_PACKAGE, MOONLIGHT_TRAMPOLINE)
                    putExtra("UUID", PC_UUID)
                    putExtra("Name", PC_NAME)
                    putExtra("AppId", APP_ID)
                    putExtra("AppName", APP_NAME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                isMoonlightRunning = true
                isReconnecting = false
                updateStatus("Moonlight launched!")
                Log.d(TAG, "Moonlight launched successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch Moonlight: ${e.message}")
                isMoonlightRunning = false
                isReconnecting = false
                updateStatus("Failed to launch Moonlight: ${e.message}")
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun updateStatus(message: String) {
        Log.d(TAG, "Status: $message")
        binding.statusText.text = message
        binding.wifiStatus.text = if (isWifiAvailable) "WiFi: ✓ Connected" else "WiFi: ✗ Disconnected"
        binding.moonlightStatus.text = if (isMoonlightRunning) "Moonlight: ✓ Running" else "Moonlight: ✗ Not running"
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}
