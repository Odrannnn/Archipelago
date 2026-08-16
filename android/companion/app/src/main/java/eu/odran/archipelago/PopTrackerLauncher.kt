package eu.odran.archipelago

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Opens the user's PopTracker Android app with an Archipelago connection preset. */
object PopTrackerLauncher {
    private const val PACKAGE_NAME = "io.github.poptracker.android"
    private const val ACTIVITY_NAME = "io.github.poptracker.android.PopTrackerActivity"
    private const val GAME_NAME = "Metroid Fusion"

    fun launch(context: Context, host: String, slot: String, password: String) {
        require(host.isNotBlank()) { "The room has no active server address." }
        require(slot.isNotBlank()) { "The room has no selected player." }
        context.startActivity(
            Intent().apply {
                component = ComponentName(PACKAGE_NAME, ACTIVITY_NAME)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("game", GAME_NAME)
                putExtra("ap_host", host)
                putExtra("ap_slot", slot)
                putExtra("ap_password", password)
            },
        )
    }
}
