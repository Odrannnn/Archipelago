package eu.odran.archipelago

import android.content.Context

data class RoomSessionSnapshot(
    val activeRoom: JoinedRoom?,
    val rooms: List<JoinedRoom>,
    val serverSettings: ServerSettings,
    val bridgeConnectionState: RoomConnectionState?,
)

data class RoomRefreshResult(
    val previous: JoinedRoom,
    val updated: JoinedRoom,
    val previousAddress: String?,
    val updatedAddress: String?,
) {
    val portChanged: Boolean get() = previous.port != updated.port
    val addressChanged: Boolean get() = previousAddress != updatedAddress
}

/**
 * Single entry point for the selected room, player, server address, and bridge-facing state.
 * Persistence remains in [JoinedRoomStore], while all cross-screen coordination happens here.
 */
object RoomSessionRepository {
    @Synchronized
    fun snapshot(context: Context): RoomSessionSnapshot = RoomSessionSnapshot(
        activeRoom = JoinedRoomStore.load(context),
        rooms = JoinedRoomStore.loadAll(context),
        serverSettings = ServerSettings.load(context),
        bridgeConnectionState = BridgeService.connectionState(),
    )

    @Synchronized
    fun activeRoom(context: Context): JoinedRoom? = JoinedRoomStore.load(context)

    @Synchronized
    fun rooms(context: Context): List<JoinedRoom> = JoinedRoomStore.loadAll(context)

    @Synchronized
    fun activate(context: Context, room: HostedRoom, invite: RoomInvite? = null): JoinedRoom =
        JoinedRoomStore.save(context, room, invite).also { synchronizeServerAddress(context, it) }

    @Synchronized
    fun select(context: Context, roomId: String): JoinedRoom? =
        JoinedRoomStore.select(context, roomId)?.also { synchronizeServerAddress(context, it) }

    @Synchronized
    fun synchronizeActive(context: Context, refreshed: HostedRoom): RoomRefreshResult? {
        val previous = JoinedRoomStore.load(context)?.takeIf { it.roomId == refreshed.roomId }
            ?: return null
        val previousAddress = previous.serverAddress()
        val updated = JoinedRoomStore.save(context, refreshed)
        val updatedAddress = updated.serverAddress()
        synchronizeServerAddress(context, updated)
        return RoomRefreshResult(previous, updated, previousAddress, updatedAddress)
    }

    @Synchronized
    fun setForceLocalItemsFromServer(context: Context, roomId: String, enabled: Boolean): JoinedRoom? =
        JoinedRoomStore.setForceLocalItemsFromServer(context, roomId, enabled)

    @Synchronized
    fun remove(context: Context, roomId: String): JoinedRoom? =
        JoinedRoomStore.delete(context, roomId)?.also { synchronizeServerAddress(context, it) }

    private fun synchronizeServerAddress(context: Context, room: JoinedRoom) {
        val address = room.serverAddress() ?: return
        val settings = ServerSettings.load(context)
        if (settings.address != address) ServerSettings.save(context, address, settings.password)
    }

    private fun JoinedRoom.serverAddress(): String? =
        port.takeIf { it > 0 }?.let(HostedRoomReconnectPolicy::serverAddress)
}
