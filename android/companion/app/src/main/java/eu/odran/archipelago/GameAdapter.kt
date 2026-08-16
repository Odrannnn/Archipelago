package eu.odran.archipelago

import org.json.JSONObject

/** Authentication data discovered inside a compatible patched game ROM. */
interface GameRomInfo {
    val name: String
    val auth: String
}

/** Minimum game state needed by the shared Archipelago synchronization loop. */
interface GameSnapshot {
    val receivedItemCount: Int
    val hasReachedGoal: Boolean
    val checkedLocationNames: Set<String>
}

/** Game-specific connection data parsed from Archipelago's Connected packet. */
interface GameSlotData {
    val startInventory: List<String>
}

/**
 * Isolates the memory contract of one Archipelago game from the shared room
 * protocol. Emulator memory methods are called only on BridgeService's worker.
 */
interface GameAdapter {
    val gameName: String
    val apWorldVersion: String
    val itemsHandling: Int get() = 0b011

    fun detectRom(): GameRomInfo?
    fun snapshot(): GameSnapshot?
    fun parseSlotData(data: JSONObject): GameSlotData
    fun applyRemoteItemWhileInGame(itemName: String, slotData: GameSlotData): Boolean
    fun setReceivedItemCountWhileInGame(count: Int): Boolean
    fun reconcileInventoryWhileInGame(itemNames: List<String>, slotData: GameSlotData): Boolean
    fun showPlayerMessage(message: String)
}

data class DetectedGame(
    val adapter: GameAdapter,
    val romInfo: GameRomInfo,
) {
    val identity: Pair<String, String> get() = adapter.gameName to romInfo.auth
}

/** Registry of game clients supported by the current emulator bridge. */
object GameRegistry {
    private data class Registration(
        val gameName: String,
        val factory: (MGBABridgeClient) -> GameAdapter,
    )

    private val registrations = listOf(
        Registration("Metroid Fusion", ::MetroidFusionProfile),
    )

    val supportedGameNames: List<String>
        get() = registrations.map(Registration::gameName)

    fun createAdapters(bridge: MGBABridgeClient): List<GameAdapter> =
        registrations.map { registration -> registration.factory(bridge) }

    fun detect(adapters: List<GameAdapter>): DetectedGame? =
        adapters.firstNotNullOfOrNull { adapter ->
            adapter.detectRom()?.let { romInfo -> DetectedGame(adapter, romInfo) }
        }

    fun patchedRomDescription(): String = when (supportedGameNames.size) {
        0 -> "compatible patched ROM"
        1 -> "patched ${supportedGameNames.single()} ROM"
        else -> "compatible patched ROM (${supportedGameNames.joinToString()})"
    }
}
