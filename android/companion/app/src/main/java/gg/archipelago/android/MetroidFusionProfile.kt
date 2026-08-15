package gg.archipelago.android

import android.util.Base64

/**
 * Runtime memory contract for ArchipelagoMine Metroid Fusion APWorld v1.22.4.
 *
 * This is intentionally a profile, not a general-purpose memory editor. All
 * mutable operations are preceded by the same in-game guard used by that
 * APWorld's BizHawk client.
 */
class MetroidFusionProfile(private val bridge: MGBABridgeClient) {
    data class Snapshot(
        val gameMode: Int,
        val minorLocationBits: ByteArray,
        val majorLocationBits: ByteArray,
        val receivedItemCount: Int,
        val area: Int,
        val room: Int,
    ) {
        val hasReachedCredits: Boolean get() = gameMode == CREDITS_MODE
    }

    data class RomInfo(
        val name: String,
        val auth: String,
        val generationVersion: List<Int>,
        val patchingVersion: List<Int>,
    )

    /** Reads and validates the APWorld's patched 20-byte ROM identifier. */
    fun romInfoOrNull(): RomInfo? {
        val nameBytes = bridge.read(GbaMemoryDomain.ROM, ROM_NAME_LOCATION, ROM_NAME_LENGTH)
        val name = nameBytes.decodeToString().trimEnd('\u0000')
        if (!name.startsWith(ROM_PREFIX)) return null
        return RomInfo(
            name = name,
            auth = Base64.encodeToString(nameBytes, Base64.NO_WRAP),
            generationVersion = bridge.read(GbaMemoryDomain.ROM, GENERATION_VERSION_LOCATION, VERSION_LENGTH)
                .map { it.toInt() and 0xff },
            patchingVersion = bridge.read(GbaMemoryDomain.ROM, PATCHING_VERSION_LOCATION, VERSION_LENGTH)
                .map { it.toInt() and 0xff },
        )
    }

    /**
     * Poll exactly the state needed for AP location reporting, item delivery,
     * map status, and victory. Returns null outside gameplay or the credits.
     */
    fun snapshot(): Snapshot? {
        val gameMode = bridge.read(GbaMemoryDomain.IWRAM, GAME_MODE, 1)[0].unsigned()
        if (gameMode != INGAME_MODE && gameMode != CREDITS_MODE) return null
        if (gameMode == CREDITS_MODE) {
            return Snapshot(gameMode, byteArrayOf(), byteArrayOf(), 0, 0, 0)
        }
        val minor = bridge.read(GbaMemoryDomain.EWRAM, MINOR_LOCATIONS_START, MINOR_LOCATIONS_LENGTH)
        val major = bridge.read(GbaMemoryDomain.IWRAM, MAJOR_LOCATIONS_START, MAJOR_LOCATIONS_LENGTH)
        val received = bridge.read(GbaMemoryDomain.SYSTEM_BUS, ITEMS_RECEIVED_LOW, 2)
        return Snapshot(
            gameMode = gameMode,
            minorLocationBits = minor,
            majorLocationBits = major,
            receivedItemCount = received[0].unsigned() or (received[1].unsigned() shl 8),
            area = bridge.read(GbaMemoryDomain.IWRAM, CURRENT_AREA, 1)[0].unsigned(),
            room = bridge.read(GbaMemoryDomain.IWRAM, CURRENT_ROOM, 1)[0].unsigned(),
        )
    }

    /** Called by the room client to acknowledge a successfully applied item. */
    fun setReceivedItemCountWhileInGame(count: Int): Boolean {
        require(count in 0..0xffff)
        if (!isInGame()) return false
        bridge.write(
            GbaMemoryDomain.SYSTEM_BUS,
            ITEMS_RECEIVED_LOW,
            byteArrayOf((count and 0xff).toByte(), ((count ushr 8) and 0xff).toByte()),
        )
        return true
    }

    fun readIwrmByteWhileInGame(offset: Long): Int? {
        if (!isInGame()) return null
        return bridge.read(GbaMemoryDomain.IWRAM, offset, 1)[0].unsigned()
    }

    fun writeIwramByteWhileInGame(offset: Long, value: Int): Boolean {
        require(value in 0..0xff)
        if (!isInGame()) return false
        bridge.write(GbaMemoryDomain.IWRAM, offset, byteArrayOf(value.toByte()))
        return true
    }

    /**
     * Applies the exact item-side memory mutation for a received AP item.
     * The room client must call [setReceivedItemCountWhileInGame] only after
     * this method succeeds. Local items are intentionally omitted: their
     * effect is already supplied by the patched ROM itself.
     */
    fun applyRemoteItemWhileInGame(itemName: String, slotData: SlotData): Boolean {
        if (!isInGame()) return false
        return when (itemName) {
            "Infant Metroid" -> incrementByte(INFANT_METROID_COUNT)
            "Level 1 Keycard" -> setBit(KEYCARD_FLAGS, 1)
            "Level 2 Keycard" -> setBit(KEYCARD_FLAGS, 2)
            "Level 3 Keycard" -> setBit(KEYCARD_FLAGS, 3)
            "Level 4 Keycard" -> setBit(KEYCARD_FLAGS, 4)
            "Morph Ball" -> setUpgrade(0x003d, 6, 0x131c, 6)
            "Hi-Jump" -> setUpgrade(0x003d, 0, 0x131c, 0)
            "Speed Booster" -> setUpgrade(0x003d, 1, 0x131c, 1)
            "Varia Suit" -> setUpgrade(0x003d, 4, 0x131c, 4)
            "Space Jump" -> setUpgrade(0x003d, 2, 0x131c, 2)
            "Gravity Suit" -> setUpgrade(0x003d, 5, 0x131c, 5)
            "Screw Attack" -> setUpgrade(0x003d, 3, 0x131c, 3)
            "Charge Beam" -> setUpgrade(0x003b, 0, 0x131a, 0, reloadGraphics = true)
            "Wide Beam" -> setUpgrade(0x003b, 1, 0x131a, 1, reloadGraphics = true)
            "Plasma Beam" -> setUpgrade(0x003b, 2, 0x131a, 2, reloadGraphics = true)
            "Wave Beam" -> setUpgrade(0x003b, 3, 0x131a, 3, reloadGraphics = true)
            "Ice Beam" -> setUpgrade(0x003b, 4, 0x131a, 4, reloadGraphics = true)
            "Missile Data" -> {
                setUpgrade(0x003c, 0, 0x131b, 0) &&
                    addLittleEndian16(MISSILE_CURRENT, MISSILE_MAX, slotData.missileDataAmmo, 999)
            }
            "Super Missile" -> setUpgrade(0x003c, 1, 0x131b, 1)
            "Ice Missile" -> setUpgrade(0x003c, 2, 0x131b, 2)
            "Diffusion Missile" -> setUpgrade(0x003c, 3, 0x131b, 3)
            "Bomb Data" -> setUpgrade(0x003c, 4, 0x131b, 4)
            "Power Bomb Data" -> {
                setUpgrade(0x003c, 5, 0x131b, 5) &&
                    addByte(POWER_BOMB_CURRENT, POWER_BOMB_MAX, slotData.powerBombDataAmmo, 255)
            }
            "Energy Tank" -> addLittleEndian16(ENERGY_CURRENT, ENERGY_MAX, 100, 2099)
            "Missile Tank" -> addLittleEndian16(MISSILE_CURRENT, MISSILE_MAX, slotData.missileTankAmmo, 999)
            "Power Bomb Tank" -> addByte(POWER_BOMB_CURRENT, POWER_BOMB_MAX, slotData.powerBombTankAmmo, 99)
            // The APWorld may send cross-game items; they advance the receipt
            // counter but have no direct Metroid Fusion RAM effect.
            else -> true
        }
    }

    private fun isInGame(): Boolean = bridge.guard(
        GbaMemoryDomain.IWRAM,
        GAME_MODE,
        byteArrayOf(INGAME_MODE.toByte()),
    )

    private fun Byte.unsigned(): Int = toInt() and 0xff

    private fun incrementByte(offset: Long): Boolean {
        val oldValue = readIwrmByteWhileInGame(offset) ?: return false
        return writeIwramByteWhileInGame(offset, (oldValue + 1) and 0xff)
    }

    private fun setBit(offset: Long, bit: Int): Boolean {
        val oldValue = readIwrmByteWhileInGame(offset) ?: return false
        return writeIwramByteWhileInGame(offset, oldValue or (1 shl bit))
    }

    private fun setUpgrade(inventory: Long, inventoryBit: Int, toggled: Long, toggledBit: Int, reloadGraphics: Boolean = false): Boolean {
        if (!setBit(inventory, inventoryBit) || !setBit(toggled, toggledBit)) return false
        return !reloadGraphics || writeIwramByteWhileInGame(GRAPHICS_RELOAD_FLAG, 1)
    }

    private fun addByte(current: Long, maximum: Long, amount: Int, cap: Int): Boolean {
        val oldCurrent = readIwrmByteWhileInGame(current) ?: return false
        val oldMax = readIwrmByteWhileInGame(maximum) ?: return false
        if (!writeIwramByteWhileInGame(current, minOf(oldCurrent + amount, cap))) return false
        return writeIwramByteWhileInGame(maximum, minOf(oldMax + amount, cap))
    }

    private fun addLittleEndian16(current: Long, maximum: Long, amount: Int, cap: Int): Boolean {
        val oldCurrent = readLittleEndian16(current) ?: return false
        val oldMax = readLittleEndian16(maximum) ?: return false
        if (!writeLittleEndian16(current, minOf(oldCurrent + amount, cap))) return false
        return writeLittleEndian16(maximum, minOf(oldMax + amount, cap))
    }

    private fun readLittleEndian16(offset: Long): Int? {
        val low = readIwrmByteWhileInGame(offset) ?: return null
        val high = readIwrmByteWhileInGame(offset + 1) ?: return null
        return low or (high shl 8)
    }

    private fun writeLittleEndian16(offset: Long, value: Int): Boolean {
        if (!writeIwramByteWhileInGame(offset, value and 0xff)) return false
        return writeIwramByteWhileInGame(offset + 1, (value ushr 8) and 0xff)
    }

    companion object {
        data class SlotData(
            val missileDataAmmo: Int,
            val missileTankAmmo: Int,
            val powerBombDataAmmo: Int,
            val powerBombTankAmmo: Int,
        )

        const val APWORLD_VERSION = "1.22.4"
        const val ROM_PREFIX = "MFU"
        const val ROM_NAME_LOCATION = 0x7fff00L
        const val ROM_NAME_LENGTH = 20
        const val GENERATION_VERSION_LOCATION = 0x7fff90L
        const val PATCHING_VERSION_LOCATION = 0x7fff93L
        const val VERSION_LENGTH = 3

        const val GAME_MODE = 0x0bdeL
        const val INGAME_MODE = 0x01
        const val CREDITS_MODE = 0x0b
        const val MINOR_LOCATIONS_START = 0x037200L
        const val MINOR_LOCATIONS_LENGTH = 16
        const val MAJOR_LOCATIONS_START = 0x06b4L
        const val MAJOR_LOCATIONS_LENGTH = 4
        const val CURRENT_AREA = 0x2cL
        const val CURRENT_ROOM = 0x2dL
        const val ITEMS_RECEIVED_LOW = 0x0e01fffeL
        const val INFANT_METROID_COUNT = 0x003eL
        const val KEYCARD_FLAGS = 0x131dL
        const val GRAPHICS_RELOAD_FLAG = 0x5671L
        const val ENERGY_CURRENT = 0x1310L
        const val ENERGY_MAX = 0x1312L
        const val MISSILE_CURRENT = 0x1314L
        const val MISSILE_MAX = 0x1316L
        const val POWER_BOMB_CURRENT = 0x1318L
        const val POWER_BOMB_MAX = 0x1319L
    }
}
