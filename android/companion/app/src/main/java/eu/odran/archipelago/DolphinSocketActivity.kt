package eu.odran.archipelago

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView

/** Displays the live status and configuration of the custom Dolphin memory socket. */
class DolphinSocketActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var telemetry: TextView
    private val refreshTelemetry = object : Runnable {
        override fun run() {
            telemetry.text = BridgeService.dolphinTelemetryText
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        telemetry = TextView(this).apply {
            text = BridgeService.dolphinTelemetryText
            CompanionUi.styleBody(this)
        }

        val content = CompanionUi.screen(this).apply {
            addView(
                CompanionUi.pageTitle(
                    this@DolphinSocketActivity,
                    "Dolphin socket",
                    "Connection and performance details for GameCube live sync.",
                ),
                CompanionUi.fullWidth(),
            )
            addView(CompanionUi.card(
                this@DolphinSocketActivity,
                "Connection status",
                "Live measurements cover the complete companion-to-Dolphin memory-service round trip.",
            ).apply {
                addView(telemetry, CompanionUi.fullWidth())
            }, CompanionUi.cardParams(this@DolphinSocketActivity))
            addView(CompanionUi.card(
                this@DolphinSocketActivity,
                "Socket configuration",
                "Install and run the Dolphin Archipelago fork. Its dedicated memory service starts with " +
                    "emulation and accepts the companion automatically on localhost port " +
                    "${DolphinSocketClient.DEFAULT_PORT}. No Dolphin.ini changes are required.",
            ), CompanionUi.cardParams(this@DolphinSocketActivity))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        SystemBarInsets.apply(window, scroll)
        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        handler.post(refreshTelemetry)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshTelemetry)
        super.onStop()
    }
}
