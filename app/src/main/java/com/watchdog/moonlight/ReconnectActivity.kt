package com.watchdog.moonlight

import android.content.Intent
import android.os.*
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ReconnectActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen over everything including lock screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContentView(R.layout.activity_reconnect)

        // Launch Moonlight after 3 seconds
        handler.postDelayed({
            launchMoonlight()
        }, 3000)
    }

    private fun launchMoonlight() {
        try {
            startActivity(Intent().apply {
                setClassName(WatchdogService.MOONLIGHT_PACKAGE, WatchdogService.MOONLIGHT_TRAMPOLINE)
                putExtra("UUID", WatchdogService.PC_UUID)
                putExtra("Name", WatchdogService.PC_NAME)
                putExtra("AppId", WatchdogService.APP_ID.toString())
                putExtra("AppName", WatchdogService.APP_NAME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            WatchdogService.isSessionActive = true
            finish()
        } catch (e: Exception) {
            findViewById<TextView>(R.id.reconnectText).text = "Launch failed:\n${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}