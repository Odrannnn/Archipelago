package gg.archipelago.android

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/** Starts the persistent bridge service and displays its current status. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refreshStatus = object : Runnable {
        override fun run() {
            status.text = BridgeService.statusText +
                "\n\nThe bridge continues running when this screen is closed. Use the notification's Stop action to end it."
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            textSize = 18f
            setPadding(48, 64, 48, 64)
            text = "Starting background bridge…"
        }
        setContentView(status)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, BridgeService::class.java))
    }

    override fun onStart() {
        super.onStart()
        handler.post(refreshStatus)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshStatus)
        super.onStop()
    }
}
