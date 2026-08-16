package eu.odran.archipelago

/** Common lifecycle for native adapters and imported standard APWorld clients. */
interface RoomSession {
    val isClosed: Boolean
    val connectedSlot: Int?
    fun connect()
    fun tick()
    fun close()
}
