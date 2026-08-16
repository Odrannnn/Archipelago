package eu.odran.archipelago

import android.content.Context
import com.chaquo.python.PyObject
import org.json.JSONArray
import org.json.JSONObject

data class ImportedGbaRomInfo(
    val game: String,
    val auth: String,
    val itemsHandling: Int,
    val wantSlotData: Boolean,
    val seedName: String,
    val client: String,
)

data class ImportedGbaTick(
    val messages: List<JSONObject>,
    val disconnect: Boolean,
    val error: String,
)

/** Serialized Kotlin wrapper around one imported APWorld's Python client. */
class PythonGbaRuntime(
    context: Context,
    bridge: MGBABridgeClient,
) : AutoCloseable {
    private val runtime: PyObject = synchronized(OfflineGenerator.runtimeLock) {
        val module = OfflineGenerator.python(context).getModule("android_bizhawk_runtime")
        checkNotNull(module.get("AndroidBizHawkRuntime")) { "Android GBA runtime class is unavailable" }
            .call(OfflineGenerator.workDirectory(context).absolutePath, AndroidBizHawkBackend(bridge))
    }

    fun probe(): ImportedGbaRomInfo? = synchronized(OfflineGenerator.runtimeLock) {
        val result = JSONObject(runtime.callAttr("probe").toString())
        if (!result.optBoolean("matched")) return@synchronized null
        ImportedGbaRomInfo(
            game = result.getString("game"),
            auth = result.getString("auth"),
            itemsHandling = result.optInt("items_handling", 7),
            wantSlotData = result.optBoolean("want_slot_data", true),
            seedName = result.optString("seed_name"),
            client = result.optString("client"),
        )
    }

    fun validateActive(info: ImportedGbaRomInfo): Boolean = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("validate_active", info.game, info.auth).toBoolean()
    }

    fun processPacket(packet: JSONObject) = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("process_packet", packet.toString())
        Unit
    }

    fun tick(): ImportedGbaTick = synchronized(OfflineGenerator.runtimeLock) {
        val result = JSONObject(runtime.callAttr("tick").toString())
        val messages = result.optJSONArray("messages") ?: JSONArray()
        ImportedGbaTick(
            messages = List(messages.length()) { messages.getJSONObject(it) },
            disconnect = result.optBoolean("disconnect"),
            error = result.optString("error"),
        )
    }

    fun resetConnection() = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("reset_connection")
        Unit
    }

    override fun close() = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("close")
        Unit
    }
}
