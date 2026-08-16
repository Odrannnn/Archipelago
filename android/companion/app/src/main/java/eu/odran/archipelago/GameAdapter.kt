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
    val checkedLocationNames: Set<String> get() = emptySet()
    val checkedLocationIds: Set<Long> get() = emptySet()
}

/** Game-specific connection data parsed from Archipelago's Connected packet. */
interface GameSlotData {
    val startInventory: List<String>
}

/** One item from Archipelago's authoritative ReceivedItems history. */
data class ReceivedGameItem(
    val id: Long,
    val name: String,
    val location: Long,
    val sourcePlayer: Int,
)

/**
 * Isolates the memory contract of one Archipelago game from the shared room
 * protocol. Emulator memory methods are called only on BridgeService's worker.
 */
interface GameAdapter {
    val gameName: String
    val apWorldVersion: String
    val itemsHandling: Int get() = 0b011
    val requiredBridgeProtocol: Int get() = 1
    val supportsInventoryReconciliation: Boolean get() = true
    val requiresServerSeedVerification: Boolean get() = false

    fun detectRom(): GameRomInfo?
    fun snapshot(): GameSnapshot?
    fun parseSlotData(data: JSONObject): GameSlotData
    fun applyRemoteItemWhileInGame(item: ReceivedGameItem, slotData: GameSlotData): Boolean
    fun setReceivedItemCountWhileInGame(count: Int): Boolean
    fun reconcileInventoryWhileInGame(itemNames: List<String>, slotData: GameSlotData): Boolean
    fun showPlayerMessage(message: String)

    /** Some games ask the server to resend a broader item set after slot data is known. */
    fun itemsHandlingAfterConnect(slotData: GameSlotData): Int? = null

    /** Confirms that the loaded ROM was generated for the room announced by the server. */
    fun verifyServerSeed(serverSeedName: String): Boolean = true

    /** Whether the ROM already applied this item at its local pickup location. */
    fun isItemSuppliedByPatchedRom(item: ReceivedGameItem, ownSlot: Int, slotData: GameSlotData): Boolean =
        item.sourcePlayer == ownSlot && item.location >= 0
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
        Registration("The Minish Cap", ::MinishCapProfile),
    )

    val supportedGameNames: List<String>
        get() = registrations.map(Registration::gameName)

    fun createAdapters(bridge: MGBABridgeClient): List<GameAdapter> =
        registrations.map { registration -> registration.factory(bridge) }

    fun detect(adapters: List<GameAdapter>): DetectedGame? =
        adapters.firstNotNullOfOrNull { adapter ->
            adapter.detectRom()?.let { romInfo -> DetectedGame(adapter, romInfo) }
        }

    fun patchedRomDescription(): String =
        "compatible patched GBA ROM (built-in or imported APWorld)"
}
