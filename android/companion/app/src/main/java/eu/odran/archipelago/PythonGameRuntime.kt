package eu.odran.archipelago

import org.json.JSONObject

data class DetectedGameInfo(
    val game: String,
    val auth: String,
    val itemsHandling: Int,
    val wantSlotData: Boolean,
    val seedName: String,
    val client: String,
    val tags: List<String> = emptyList(),
)

data class GameRuntimeTick(
    val messages: List<JSONObject>,
    val console: List<ClientConsoleMessage>,
    val disconnect: Boolean,
    val error: String,
    val diagnostic: String,
)

data class ClientConsoleMessage(val kind: String, val text: String)

data class GameRuntimeCommandResult(
    val console: List<ClientConsoleMessage>,
    val actions: List<JSONObject>,
)

internal fun JSONObject.consoleMessages(): List<ClientConsoleMessage> {
    val values = optJSONArray("console") ?: return emptyList()
    return List(values.length()) { index ->
        val value = values.getJSONObject(index)
        ClientConsoleMessage(value.optString("kind", "output"), value.optString("text"))
    }
}

internal fun JSONObject.runtimeActions(): List<JSONObject> {
    val values = optJSONArray("actions") ?: return emptyList()
    return List(values.length()) { values.getJSONObject(it) }
}

/** Emulator-agnostic contract between a game client and the room transport. */
interface PythonGameRuntime : AutoCloseable {
    fun probe(): DetectedGameInfo?
    fun validateActive(info: DetectedGameInfo): Boolean
    fun processPacket(packet: JSONObject)
    fun executeCommand(command: String): GameRuntimeCommandResult
    fun tick(emulatorAvailable: Boolean = true): GameRuntimeTick
    fun resetConnection()
    fun emulatorDetached() = Unit
    fun emulatorReattached()
}
