package eu.odran.archipelago

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime memory contract for ArchipelagoMine Metroid Fusion APWorld v1.22.4.
 *
 * This is intentionally a profile, not a general-purpose memory editor. All
 * mutable operations are preceded by the same in-game guard used by that
 * APWorld's BizHawk client.
 */
class MetroidFusionProfile(private val bridge: MGBABridgeClient) : GameAdapter {
    override val gameName: String = "Metroid Fusion"
    override val apWorldVersion: String = APWORLD_VERSION

    data class Snapshot(
        val gameMode: Int,
        val minorLocationBits: ByteArray,
        val majorLocationBits: ByteArray,
        override val receivedItemCount: Int,
        val area: Int,
        val room: Int,
    ) : GameSnapshot {
        override val hasReachedGoal: Boolean get() = gameMode == CREDITS_MODE
        override val checkedLocationNames: Set<String>
            get() = MetroidFusionLocations.checkedNames(this)
    }

    data class RomInfo(
        override val name: String,
        override val auth: String,
        val generationVersion: List<Int>,
        val patchingVersion: List<Int>,
    ) : GameRomInfo

    /** Reads and validates the APWorld's patched 20-byte ROM identifier. */
    override fun detectRom(): RomInfo? {
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
    override fun snapshot(): Snapshot? {
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
    override fun setReceivedItemCountWhileInGame(count: Int): Boolean {
        require(count in 0..0xffff)
        if (!isInGame()) return false
        bridge.write(
            GbaMemoryDomain.SYSTEM_BUS,
            ITEMS_RECEIVED_LOW,
            byteArrayOf((count and 0xff).toByte(), ((count ushr 8) and 0xff).toByte()),
        )
        return true
    }

    override fun showPlayerMessage(message: String) {
        bridge.showMessage(message)
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
    override fun applyRemoteItemWhileInGame(itemName: String, slotData: GameSlotData): Boolean {
        val data = slotData as? SlotData
            ?: error("Metroid Fusion received incompatible slot data")
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
                    addLittleEndian16(MISSILE_CURRENT, MISSILE_MAX, data.missileDataAmmo, 999)
            }
            "Super Missile" -> setUpgrade(0x003c, 1, 0x131b, 1)
            "Ice Missile" -> setUpgrade(0x003c, 2, 0x131b, 2)
            "Diffusion Missile" -> setUpgrade(0x003c, 3, 0x131b, 3)
            "Bomb Data" -> setUpgrade(0x003c, 4, 0x131b, 4)
            "Power Bomb Data" -> {
                setUpgrade(0x003c, 5, 0x131b, 5) &&
                    addByte(POWER_BOMB_CURRENT, POWER_BOMB_MAX, data.powerBombDataAmmo, 255)
            }
            "Energy Tank" -> addLittleEndian16(ENERGY_CURRENT, ENERGY_MAX, 100, 2099)
            "Missile Tank" -> addLittleEndian16(MISSILE_CURRENT, MISSILE_MAX, data.missileTankAmmo, 999)
            "Power Bomb Tank" -> addByte(POWER_BOMB_CURRENT, POWER_BOMB_MAX, data.powerBombTankAmmo, 99)
            // The APWorld may send cross-game items; they advance the receipt
            // counter but have no direct Metroid Fusion RAM effect.
            else -> true
        }
    }

    /**
     * Rebuilds persistent AP inventory state from the authoritative item
     * history. This restores items after loading an older in-game save while
     * the receipt counter in SRAM remains ahead of that save.
     *
     * Capacity maxima are assigned from totals rather than incremented, so
     * running this every watcher tick cannot duplicate tanks.
     */
    override fun reconcileInventoryWhileInGame(itemNames: List<String>, slotData: GameSlotData): Boolean {
        val data = slotData as? SlotData
            ?: error("Metroid Fusion received incompatible slot data")
        if (!isInGame()) return false

        var beamFlags = 0
        var missileFlags = 0
        var suitFlags = 0
        var keycardFlags = 0x01
        itemNames.toSet().forEach { itemName ->
            when (itemName) {
                "Charge Beam" -> beamFlags = beamFlags or (1 shl 0)
                "Wide Beam" -> beamFlags = beamFlags or (1 shl 1)
                "Plasma Beam" -> beamFlags = beamFlags or (1 shl 2)
                "Wave Beam" -> beamFlags = beamFlags or (1 shl 3)
                "Ice Beam" -> beamFlags = beamFlags or (1 shl 4)
                "Missile Data" -> missileFlags = missileFlags or (1 shl 0)
                "Super Missile" -> missileFlags = missileFlags or (1 shl 1)
                "Ice Missile" -> missileFlags = missileFlags or (1 shl 2)
                "Diffusion Missile" -> missileFlags = missileFlags or (1 shl 3)
                "Bomb Data" -> missileFlags = missileFlags or (1 shl 4)
                "Power Bomb Data" -> missileFlags = missileFlags or (1 shl 5)
                "Hi-Jump" -> suitFlags = suitFlags or (1 shl 0)
                "Speed Booster" -> suitFlags = suitFlags or (1 shl 1)
                "Space Jump" -> suitFlags = suitFlags or (1 shl 2)
                "Screw Attack" -> suitFlags = suitFlags or (1 shl 3)
                "Varia Suit" -> suitFlags = suitFlags or (1 shl 4)
                "Gravity Suit" -> suitFlags = suitFlags or (1 shl 5)
                "Morph Ball" -> suitFlags = suitFlags or (1 shl 6)
                "Level 1 Keycard" -> keycardFlags = keycardFlags or (1 shl 1)
                "Level 2 Keycard" -> keycardFlags = keycardFlags or (1 shl 2)
                "Level 3 Keycard" -> keycardFlags = keycardFlags or (1 shl 3)
                "Level 4 Keycard" -> keycardFlags = keycardFlags or (1 shl 4)
            }
        }

        val infantMetroids = itemNames.count { it == "Infant Metroid" }.coerceAtMost(0xff)
        val missileMax = (
            data.missileDataAmmo +
                itemNames.count { it == "Missile Tank" } * data.missileTankAmmo
            ).coerceAtMost(999)
        val energyMax = (99 + itemNames.count { it == "Energy Tank" } * 100).coerceAtMost(2099)
        val powerBombMax = (
            data.powerBombDataAmmo +
                itemNames.count { it == "Power Bomb Tank" } * data.powerBombTankAmmo
            ).coerceAtMost(99)

        // Three compact reads replace dozens of per-upgrade bridge round trips.
        val inventory = bridge.read(GbaMemoryDomain.IWRAM, BEAM_INVENTORY, 4)
        val capacityAndToggles = bridge.read(GbaMemoryDomain.IWRAM, ENERGY_MAX, 12)
        val keycardFlash = bridge.read(GbaMemoryDomain.IWRAM, KEYCARD_FLASH_FLAGS, 1)[0].unsigned()
        if (!isInGame()) return false

        var success = true
        fun restoreByte(offset: Long, oldValue: Int, requiredBits: Int) {
            val restored = oldValue or requiredBits
            if (restored != oldValue) success = success && writeIwramByteWhileInGame(offset, restored)
        }
        restoreByte(BEAM_INVENTORY, inventory[0].unsigned(), beamFlags)
        restoreByte(MISSILE_INVENTORY, inventory[1].unsigned(), missileFlags)
        restoreByte(SUIT_INVENTORY, inventory[2].unsigned(), suitFlags)
        restoreByte(BEAM_TOGGLES, capacityAndToggles[8].unsigned(), beamFlags)
        restoreByte(MISSILE_TOGGLES, capacityAndToggles[9].unsigned(), missileFlags)
        restoreByte(SUIT_TOGGLES, capacityAndToggles[10].unsigned(), suitFlags)
        restoreByte(KEYCARD_FLAGS, capacityAndToggles[11].unsigned(), keycardFlags)
        restoreByte(KEYCARD_FLASH_FLAGS, keycardFlash, keycardFlags)

        val oldInfantMetroids = inventory[3].unsigned()
        if (infantMetroids > oldInfantMetroids) {
            success = success && writeIwramByteWhileInGame(INFANT_METROID_COUNT, infantMetroids)
        }
        val oldEnergyMax = capacityAndToggles[0].unsigned() or (capacityAndToggles[1].unsigned() shl 8)
        val oldMissileMax = capacityAndToggles[4].unsigned() or (capacityAndToggles[5].unsigned() shl 8)
        val oldPowerBombMax = capacityAndToggles[7].unsigned()
        if (energyMax > oldEnergyMax) success = success && writeLittleEndian16(ENERGY_MAX, energyMax)
        if (missileMax > oldMissileMax) success = success && writeLittleEndian16(MISSILE_MAX, missileMax)
        if (powerBombMax > oldPowerBombMax) {
            success = success && writeIwramByteWhileInGame(POWER_BOMB_MAX, powerBombMax)
        }
        if (beamFlags != 0 &&
            ((inventory[0].unsigned() and beamFlags) != beamFlags ||
                (capacityAndToggles[8].unsigned() and beamFlags) != beamFlags)
        ) {
            success = success && writeIwramByteWhileInGame(GRAPHICS_RELOAD_FLAG, 1)
        }
        return success
    }

    override fun parseSlotData(data: JSONObject): GameSlotData = SlotData(
        missileDataAmmo = data.optInt("MissileDataAmmo", 10),
        missileTankAmmo = data.optInt("MissileTankAmmo", 5),
        powerBombDataAmmo = data.optInt("PowerBombDataAmmo", 10),
        powerBombTankAmmo = data.optInt("PowerBombTankAmmo", 2),
        startInventory = jsonStringList(data.optJSONArray("StartInventory")),
    )

    private fun jsonStringList(values: JSONArray?): List<String> =
        if (values == null) emptyList() else
            (0 until values.length()).map(values::getString)

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
            override val startInventory: List<String>,
        ) : GameSlotData

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
        const val BEAM_INVENTORY = 0x003bL
        const val MISSILE_INVENTORY = 0x003cL
        const val SUIT_INVENTORY = 0x003dL
        const val INFANT_METROID_COUNT = 0x003eL
        const val KEYCARD_FLAGS = 0x131dL
        const val KEYCARD_FLASH_FLAGS = 0x001cL
        const val GRAPHICS_RELOAD_FLAG = 0x5671L
        const val ENERGY_CURRENT = 0x1310L
        const val ENERGY_MAX = 0x1312L
        const val MISSILE_CURRENT = 0x1314L
        const val MISSILE_MAX = 0x1316L
        const val POWER_BOMB_CURRENT = 0x1318L
        const val POWER_BOMB_MAX = 0x1319L
        const val BEAM_TOGGLES = 0x131aL
        const val MISSILE_TOGGLES = 0x131bL
        const val SUIT_TOGGLES = 0x131cL
    }
}
