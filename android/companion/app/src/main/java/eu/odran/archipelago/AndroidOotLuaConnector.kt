package eu.odran.archipelago

import android.content.Context
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JsePlatform

/**
 * Runs Archipelago's upstream connector_oot.lua against the generic N64 memory backend.
 *
 * The Lua script's socket main loop is replaced by one direct exchange function. All game
 * semantics remain in the upstream script: location and collectible reads, item delivery,
 * player-name writes, DeathLink, safe-state checks, and goal detection.
 */
internal class AndroidOotLuaConnector(
    context: Context,
    private val backend: AndroidRetroArchN64Backend,
) {
    private val applicationContext = context.applicationContext
    private var globals: Globals? = null
    private var exchangeFunction: LuaValue? = null
    private var identityFunction: LuaValue? = null

    @Synchronized
    fun isOotRom(): Boolean = runCatching {
        backend.readRom(0x20, 20)
            .toString(Charsets.US_ASCII)
            .trimEnd('\u0000', ' ') == OOT_ROM_TITLE
    }.getOrDefault(false)

    @Synchronized
    fun exchange(payloadJson: String): String {
        check(isOotRom()) { "The active N64 ROM is not Ocarina of Time" }
        val function = exchangeFunction ?: initialise().also { exchangeFunction = it }
        backend.beginSnapshot()
        return try {
            function.call(LuaValue.valueOf(payloadJson)).checkjstring()
        } finally {
            backend.endSnapshot()
        }
    }

    @Synchronized
    fun identity(): String {
        check(isOotRom()) { "The active N64 ROM is not Ocarina of Time" }
        if (exchangeFunction == null) exchangeFunction = initialise()
        return checkNotNull(identityFunction).call().checkjstring()
    }

    @Synchronized
    fun reset() {
        exchangeFunction = null
        identityFunction = null
        globals = null
    }

    private fun initialise(): LuaValue {
        val created = JsePlatform.standardGlobals()
        created.set("mainmemory", memoryTable())
        created.set("memory", memoryTable())
        created.set("client", LuaTable().apply {
            set("getversion", constantString("2.9.1"))
            set("screenwidth", constantInt(640))
        })
        created.set("gui", LuaTable().apply {
            set("addmessage", noOp())
            set("drawText", noOp())
        })
        created.set("emu", LuaTable().apply {
            set("frameadvance", noOp())
        })
        created.set("bit", bitTable())

        preload(created, "socket", null)
        preload(created, "json", "oot_lua/json.lua")
        preload(created, "common", "oot_lua/common.lua")
        preload(created, "lua_5_3_compat", "oot_lua/lua_5_3_compat.lua")

        val original = readAsset("oot_lua/connector_oot.lua")
        val connector = original.replace(
            Regex("main\\(\\)\\s*$"),
            ANDROID_EXCHANGE,
        )
        check(connector != original) { "Upstream OoT connector entry point was not found" }
        created.load(connector, "connector_oot.lua").call()
        globals = created
        identityFunction = created.get("android_identity").also {
            check(it.isfunction()) { "Android OoT identity function was not created" }
        }
        return created.get("android_exchange").also {
            check(it.isfunction()) { "Android OoT exchange function was not created" }
        }
    }

    private fun preload(globals: Globals, module: String, asset: String?) {
        val loader = object : ZeroArgFunction() {
            override fun call(): LuaValue = asset?.let {
                globals.load(readAsset(it), it).call().let { value ->
                    if (value.isnil()) LuaValue.TRUE else value
                }
            } ?: LuaTable()
        }
        globals.get("package").get("preload").set(module, loader)
    }

    private fun memoryTable(): LuaTable = LuaTable().apply {
        set("read_u8", readInteger(1, littleEndian = false))
        set("readbyte", readInteger(1, littleEndian = false))
        set("read_u16_be", readInteger(2, littleEndian = false))
        set("read_u24_be", readInteger(3, littleEndian = false))
        set("read_u32_be", readInteger(4, littleEndian = false))
        set("read_u16_le", readInteger(2, littleEndian = true))
        set("readbyterange", object : TwoArgFunction() {
            override fun call(address: LuaValue, length: LuaValue): LuaValue {
                val bytes = backend.readRdram(address.unsignedAddress(), length.checkint())
                return LuaTable().apply {
                    bytes.forEachIndexed { index, value ->
                        set(index, LuaValue.valueOf(value.toInt() and 0xff))
                    }
                }
            }
        })
        set("write_u8", writeInteger(1, littleEndian = false))
        set("writebyte", writeInteger(1, littleEndian = false))
        set("write_u16_be", writeInteger(2, littleEndian = false))
        set("write_u24_be", writeInteger(3, littleEndian = false))
        set("write_u32_be", writeInteger(4, littleEndian = false))
    }

    private fun readInteger(width: Int, littleEndian: Boolean) = object : OneArgFunction() {
        override fun call(address: LuaValue): LuaValue {
            val bytes = backend.readRdram(address.unsignedAddress(), width)
            val ordered = if (littleEndian) bytes.reversedArray() else bytes
            val value = ordered.fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 0xff) }
            return LuaValue.valueOf(value.toDouble())
        }
    }

    private fun writeInteger(width: Int, littleEndian: Boolean) = object : TwoArgFunction() {
        override fun call(address: LuaValue, value: LuaValue): LuaValue {
            val raw = value.unsigned32()
            val bytes = ByteArray(width) { index ->
                ((raw ushr ((width - index - 1) * 8)) and 0xff).toByte()
            }.let { if (littleEndian) it.reversedArray() else it }
            backend.writeRdram(address.unsignedAddress(), bytes)
            return LuaValue.NIL
        }
    }

    private fun bitTable(): LuaTable = LuaTable().apply {
        set("check", object : TwoArgFunction() {
            override fun call(value: LuaValue, bit: LuaValue): LuaValue = LuaValue.valueOf(
                value.unsigned32() and (1L shl (bit.checkint() and 31)) != 0L,
            )
        })
        set("set", binaryBit { value, bit -> value or (1L shl (bit.toInt() and 31)) })
        set("clear", binaryBit { value, bit -> value and (1L shl (bit.toInt() and 31)).inv() })
        set("rshift", binaryBit { value, count -> value ushr (count.toInt() and 31) })
        set("lshift", binaryBit { value, count -> value shl (count.toInt() and 31) })
        set("band", binaryBit { left, right -> left and right })
        set("bor", binaryBit { left, right -> left or right })
        set("bnot", object : OneArgFunction() {
            override fun call(value: LuaValue): LuaValue = luaUnsigned(value.unsigned32().inv())
        })
    }

    private fun binaryBit(operation: (Long, Long) -> Long) = object : TwoArgFunction() {
        override fun call(left: LuaValue, right: LuaValue): LuaValue =
            luaUnsigned(operation(left.unsigned32(), right.unsigned32()))
    }

    private fun luaUnsigned(value: Long): LuaValue = LuaValue.valueOf((value and UINT32_MASK).toDouble())

    private fun LuaValue.unsigned32(): Long = checkdouble().toLong() and UINT32_MASK

    private fun LuaValue.unsignedAddress(): Long = checkdouble().toLong() and UINT32_MASK

    private fun constantString(value: String) = object : ZeroArgFunction() {
        override fun call(): LuaValue = LuaValue.valueOf(value)
    }

    private fun constantInt(value: Int) = object : ZeroArgFunction() {
        override fun call(): LuaValue = LuaValue.valueOf(value)
    }

    private fun noOp() = object : ZeroArgFunction() {
        override fun call(): LuaValue = LuaValue.NIL
    }

    private fun readAsset(path: String): String =
        applicationContext.assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        private const val OOT_ROM_TITLE = "THE LEGEND OF ZELDA"
        private const val UINT32_MASK = 0xffff_ffffL

        private val ANDROID_EXCHANGE = """
            function android_identity()
                return get_player_name()
            end

            function android_exchange(block_json)
                process_block(json.decode(block_json))
                local retTable = {}
                retTable["playerName"] = get_player_name()
                retTable["scriptVersion"] = script_version
                retTable["deathlinkActive"] = deathlink_enabled()
                if InSafeState() then
                    retTable["locations"] = check_all_locations(master_quest_table_address)
                    retTable["collectibles"] = check_collectibles()
                    retTable["isDead"] = get_death_state()
                    retTable["gameComplete"] = is_game_complete()
                end
                return json.encode(retTable)
            end
        """.trimIndent()
    }
}
