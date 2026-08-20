package eu.odran.archipelago

enum class RoomConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

/** Common lifecycle for a standard APWorld client connected to a room. */
interface RoomSession {
    val isClosed: Boolean
    val connectedSlot: Int?
    val automaticRetryAllowed: Boolean
        get() = true
    fun connect()
    fun tick(emulatorAvailable: Boolean = true)
    fun close()
}
