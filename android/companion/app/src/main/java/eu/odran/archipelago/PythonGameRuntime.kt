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
    val disconnect: Boolean,
    val error: String,
    val diagnostic: String,
)

/** Emulator-agnostic contract between a game client and the room transport. */
interface PythonGameRuntime : AutoCloseable {
    fun probe(): DetectedGameInfo?
    fun validateActive(info: DetectedGameInfo): Boolean
    fun processPacket(packet: JSONObject)
    fun tick(emulatorAvailable: Boolean = true): GameRuntimeTick
    fun resetConnection()
    fun emulatorDetached() = Unit
    fun emulatorReattached()
}
