package eu.odran.archipelago

import org.json.JSONObject

/** Runtime memory contract for The Minish Cap APWorld v0.3.1. */
class MinishCapProfile(private val bridge: MGBABridgeClient) : GameAdapter {
    override val gameName: String = "The Minish Cap"
    override val apWorldVersion: String = APWORLD_VERSION
    override val itemsHandling: Int = 0b101
    override val requiredBridgeProtocol: Int = BridgeProtocol.GUARDED_WRITE_PROTOCOL_VERSION
    override val supportsInventoryReconciliation: Boolean = false
    override val requiresServerSeedVerification: Boolean = true

    data class RomInfo(
        override val name: String,
        override val auth: String,
    ) : GameRomInfo

    data class SlotData(
        val remoteItems: Boolean,
        val version: Int,
        override val startInventory: List<String> = emptyList(),
    ) : GameSlotData

    data class Snapshot(
        val gameTask: Int,
        override val receivedItemCount: Int,
        override val checkedLocationIds: Set<Long>,
    ) : GameSnapshot {
        override val hasReachedGoal: Boolean get() = gameTask == GOAL_TASK
    }

    override fun detectRom(): RomInfo? {
        val identifier = bridge.read(GbaMemoryDomain.ROM, GAME_IDENTIFIER, GAME_IDENTIFIER_LENGTH)
            .decodeToString()
        if (identifier != GAME_IDENTIFIER_TEXT) return null
        val playerName = bridge.read(GbaMemoryDomain.ROM, PLAYER_NAME, PLAYER_NAME_LENGTH)
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .decodeToString()
        if (playerName.isBlank()) return null
        return RomInfo(name = playerName, auth = playerName)
    }

    override fun verifyServerSeed(serverSeedName: String): Boolean {
        val embedded = bridge.read(
            GbaMemoryDomain.ROM,
            SEED_NAME,
            serverSeedName.encodeToByteArray().size,
        ).decodeToString().trimEnd('\u0000')
        return embedded.isNotBlank() && serverSeedName.contains(embedded)
    }

    override fun snapshot(): Snapshot? {
        val task = taskState()
        val gameTask = task.first
        if (gameTask != NORMAL_TASK && task.second != NORMAL_SUBSTATE && gameTask != GOAL_TASK) return null
        if (gameTask == GOAL_TASK) return Snapshot(gameTask, 0, emptySet())
        val received = bridge.read(GbaMemoryDomain.EWRAM, RECEIVED_INDEX, 2)
        val locationMemory = bridge.read(
            GbaMemoryDomain.EWRAM,
            MinishCapLocations.RAM_START,
            MinishCapLocations.RAM_LENGTH,
        )
        return Snapshot(
            gameTask = gameTask,
            receivedItemCount = (received[0].unsigned() shl 8) or received[1].unsigned(),
            checkedLocationIds = MinishCapLocations.checkedIds(locationMemory),
        )
    }

    override fun parseSlotData(data: JSONObject): SlotData = SlotData(
        remoteItems = data.opt("remote_items").asBoolean(),
        version = data.optInt("version", 0),
    )

    override fun itemsHandlingAfterConnect(slotData: GameSlotData): Int? {
        val data = slotData as? SlotData ?: error("The Minish Cap received incompatible slot data")
        return if (data.remoteItems) 0b111 else null
    }

    override fun isItemSuppliedByPatchedRom(
        item: ReceivedGameItem,
        ownSlot: Int,
        slotData: GameSlotData,
    ): Boolean = false

    override fun applyRemoteItemWhileInGame(item: ReceivedGameItem, slotData: GameSlotData): Boolean {
        require(item.id in 0..0xffff) { "The Minish Cap received an invalid item id ${item.id}" }
        if (!isInGame()) return false
        val itemBytes = byteArrayOf((item.id ushr 8).toByte(), (item.id and 0xff).toByte())
        return bridge.guardedWrite(
            GbaMemoryDomain.EWRAM,
            ITEM_QUEUE,
            itemBytes,
            listOf(
                Triple(GbaMemoryDomain.EWRAM, ITEM_QUEUE, byteArrayOf(0, 0)),
                Triple(GbaMemoryDomain.EWRAM, PLAYER_SAFE, byteArrayOf(1)),
            ),
        )
    }

    override fun setReceivedItemCountWhileInGame(count: Int): Boolean {
        require(count in 0..0xffff)
        if (!isInGame()) return false
        bridge.write(
            GbaMemoryDomain.EWRAM,
            RECEIVED_INDEX,
            byteArrayOf((count ushr 8).toByte(), (count and 0xff).toByte()),
        )
        return true
    }

    override fun reconcileInventoryWhileInGame(itemNames: List<String>, slotData: GameSlotData): Boolean = true

    override fun showPlayerMessage(message: String) = bridge.showMessage(message)

    private fun taskState(): Pair<Int, Int> {
        val state = bridge.read(GbaMemoryDomain.IWRAM, GAME_TASK, 3)
        return state[0].unsigned() to state[2].unsigned()
    }

    private fun isInGame(): Boolean {
        val (gameTask, taskSubstate) = taskState()
        return gameTask == NORMAL_TASK || taskSubstate == NORMAL_SUBSTATE
    }

    private fun Any?.asBoolean(): Boolean = when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        else -> toString().equals("true", ignoreCase = true) || toString() == "1"
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff

    companion object {
        const val APWORLD_VERSION = "0.3.1"
        const val GAME_IDENTIFIER = 0xA0L
        const val GAME_IDENTIFIER_LENGTH = 8
        const val GAME_IDENTIFIER_TEXT = "GBAZELDA"
        const val PLAYER_NAME = 0x600L
        const val PLAYER_NAME_LENGTH = 16
        const val SEED_NAME = 0x620L
        const val GAME_TASK = 0x1002L
        const val NORMAL_TASK = 0x02
        const val NORMAL_SUBSTATE = 0x02
        const val GOAL_TASK = 0x04
        const val RECEIVED_INDEX = 0x2A44L
        const val PLAYER_SAFE = 0x2A4AL
        const val ITEM_QUEUE = 0x3FF10L
    }
}
