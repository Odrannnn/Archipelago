package gg.archipelago.android

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
        val savedSettings = ServerSettings.load(this)
        status = TextView(this).apply {
            textSize = 18f
            text = "Starting background bridge…"
        }

        val address = EditText(this).apply {
            hint = "Server address, e.g. archipelago.gg:45657"
            setSingleLine(true)
            setText(savedSettings.address)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val password = EditText(this).apply {
            hint = "Room password (optional)"
            setSingleLine(true)
            setText(savedSettings.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = Button(this).apply {
            text = "Save and connect"
            setOnClickListener {
                ServerSettings.save(this@MainActivity, address.text.toString(), password.text.toString())
                startForegroundService(
                    Intent(this@MainActivity, BridgeService::class.java)
                        .setAction(BridgeService.ACTION_RECONNECT),
                )
                status.text = "Settings saved · reconnecting…"
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(this@MainActivity).apply {
                text = "Archipelago Android Companion"
                textSize = 24f
            })
            addView(address, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(password, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(save, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(ScrollView(this).apply { addView(content) })

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
