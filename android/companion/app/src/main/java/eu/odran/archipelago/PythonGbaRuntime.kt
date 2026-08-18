package eu.odran.archipelago

import android.content.Context
import com.chaquo.python.PyObject
import org.json.JSONArray
import org.json.JSONObject

/** Serialized Kotlin wrapper around one built-in or imported world's Python client. */
class PythonGbaRuntime(
    context: Context,
    bridge: MGBABridgeClient,
    platform: Int,
) : PythonGameRuntime {
    private val backend = AndroidBizHawkBackend(bridge, platform)
    private val platform = platform
    private val runtime: PyObject = synchronized(OfflineGenerator.runtimeLock) {
        val module = OfflineGenerator.python(context).getModule("android_bizhawk_runtime")
        checkNotNull(module.get("AndroidBizHawkRuntime")) { "Android mGBA runtime class is unavailable" }
            .call(
                OfflineGenerator.workDirectory(context).absolutePath,
                backend,
            )
    }

    override fun probe(): DetectedGameInfo? = synchronized(OfflineGenerator.runtimeLock) {
        val result = JSONObject(runtime.callAttr("probe").toString())
        if (!result.optBoolean("matched")) return@synchronized null
        DetectedGameInfo(
            game = result.getString("game"),
            auth = result.getString("auth"),
            itemsHandling = result.optInt("items_handling", 7),
            wantSlotData = result.optBoolean("want_slot_data", true),
            seedName = result.optString("seed_name"),
            client = result.optString("client"),
        )
    }

    override fun validateActive(info: DetectedGameInfo): Boolean = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("validate_active", info.game, info.auth).toBoolean()
    }

    override fun processPacket(packet: JSONObject) = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("process_packet", packet.toString())
        Unit
    }

    override fun tick(emulatorAvailable: Boolean): GameRuntimeTick = synchronized(OfflineGenerator.runtimeLock) {
        val result = JSONObject(runtime.callAttr("tick", emulatorAvailable).toString())
        val messages = result.optJSONArray("messages") ?: JSONArray()
        GameRuntimeTick(
            messages = List(messages.length()) { messages.getJSONObject(it) },
            disconnect = result.optBoolean("disconnect"),
            error = result.optString("error"),
            diagnostic = result.optString("diagnostic"),
        )
    }

    override fun resetConnection() = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("reset_connection")
        Unit
    }

    fun attachBridge(bridge: MGBABridgeClient, newPlatform: Int) =
        backend.attach(bridge, newPlatform)

    fun detachBridge(bridge: MGBABridgeClient) = backend.detach(bridge)

    fun acceptsPlatform(newPlatform: Int): Boolean = platform == newPlatform

    override fun emulatorReattached() = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("bridge_reconnected")
        Unit
    }

    override fun close() = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("close")
        Unit
    }
}
