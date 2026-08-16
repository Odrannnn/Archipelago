package eu.odran.archipelago

enum class RoomConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

/** Common lifecycle for a standard APWorld client connected to a room. */
interface RoomSession {
    val isClosed: Boolean
    val connectedSlot: Int?
    fun connect()
    fun tick()
    fun close()
}
