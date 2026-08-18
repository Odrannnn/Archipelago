package eu.odran.archipelago

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.widget.EditText

data class SohPlayer(val slot: Int, val name: String)

/** Launches the Archipelago-enabled Ship of Harkinian Android port. */
object SohLauncher {
    const val GAME_NAME = "Ship of Harkinian"

    private const val PACKAGE_NAME = "com.dishii.soh"
    private const val ACTIVITY_NAME = "$PACKAGE_NAME.MainActivity"
    private const val CONNECT_ACTION = "com.dishii.soh.action.CONNECT_ARCHIPELAGO"
    private const val ADDRESS_EXTRA = "archipelago_address"
    private const val SLOT_EXTRA = "archipelago_slot"
    private const val PASSWORD_EXTRA = "archipelago_password"
    private val PLAYER_LABEL = Regex("^(.*) \\(([^()]*)\\)$")

    fun isGame(gameName: String?): Boolean = gameName.equals(GAME_NAME, ignoreCase = true)

    /** Website room players are ordered by AP slot and rendered as `name (game)`. */
    fun players(displayNames: List<String>): List<SohPlayer> = displayNames.mapIndexedNotNull { index, label ->
        val match = PLAYER_LABEL.matchEntire(label.trim()) ?: return@mapIndexedNotNull null
        if (!isGame(match.groupValues[2].trim())) return@mapIndexedNotNull null
        val playerName = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
            ?: return@mapIndexedNotNull null
        SohPlayer(slot = index + 1, name = playerName)
    }

    fun promptAndLaunch(
        activity: Activity,
        address: String,
        playerName: String,
        onLaunched: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val password = EditText(activity).apply {
            hint = "Room password (optional)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(ServerSettings.load(activity).password)
            setSelection(text.length)
        }
        AlertDialog.Builder(activity)
            .setTitle("Launch Ship of Harkinian")
            .setMessage("Connect $playerName to ${normalizedAddress(address)}. The password is sent directly to SoH and is not placed in a URL.")
            .setView(password)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Launch SoH") { _, _ ->
                runCatching { launch(activity, address, playerName, password.text.toString()) }
                    .onSuccess { onLaunched() }
                    .onFailure(onFailure)
            }
            .show()
    }

    fun launch(context: Context, address: String, playerName: String, password: String = "") {
        val normalizedAddress = normalizedAddress(address)
        require(normalizedAddress.isNotBlank()) { "The Archipelago server address is missing." }
        require(playerName.isNotBlank()) { "The Ship of Harkinian player name is missing." }

        context.startActivity(Intent(CONNECT_ACTION).apply {
            component = ComponentName(PACKAGE_NAME, ACTIVITY_NAME)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(ADDRESS_EXTRA, normalizedAddress)
            putExtra(SLOT_EXTRA, playerName.trim())
            password.takeIf { it.isNotBlank() }?.let { putExtra(PASSWORD_EXTRA, it) }
        })
    }

    private fun normalizedAddress(address: String): String = address
        .trim()
        .replace(Regex("^[A-Za-z][A-Za-z0-9+.-]*://"), "")
        .substringBefore('/')
        .trimEnd('/')
}
