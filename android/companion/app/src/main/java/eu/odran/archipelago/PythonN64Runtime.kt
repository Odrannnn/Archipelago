package eu.odran.archipelago

import android.content.Context
import com.chaquo.python.PyObject
import org.json.JSONArray
import org.json.JSONObject

/** Runs ordinary upstream N64 BizHawk clients over RetroArch Network Commands. */
class PythonN64Runtime(
    context: Context,
    client: RetroArchNetworkClient,
) : PythonGameRuntime {
    private val backend = AndroidRetroArchN64Backend(client)
    private val runtime: PyObject = synchronized(OfflineGenerator.runtimeLock) {
        val module = OfflineGenerator.python(context).getModule("android_bizhawk_runtime")
        checkNotNull(module.get("AndroidBizHawkRuntime")) { "Android BizHawk runtime class is unavailable" }
            .call(OfflineGenerator.workDirectory(context).absolutePath, backend)
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

    override fun setForceLocalItems(enabled: Boolean): Int = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("set_force_local_items", enabled).toInt()
    }

    override fun processPacket(packet: JSONObject) = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("process_packet", packet.toString())
        Unit
    }

    override fun executeCommand(command: String): GameRuntimeCommandResult =
        synchronized(OfflineGenerator.runtimeLock) {
            val result = JSONObject(runtime.callAttr("execute_command", command).toString())
            GameRuntimeCommandResult(result.consoleMessages(), result.runtimeActions())
        }

    override fun tick(emulatorAvailable: Boolean): GameRuntimeTick = synchronized(OfflineGenerator.runtimeLock) {
        val result = JSONObject(runtime.callAttr("tick", emulatorAvailable).toString())
        val messages = result.optJSONArray("messages") ?: JSONArray()
        GameRuntimeTick(
            messages = List(messages.length()) { messages.getJSONObject(it) },
            console = result.consoleMessages(),
            disconnect = result.optBoolean("disconnect"),
            error = result.optString("error"),
            diagnostic = result.optString("diagnostic"),
        )
    }

    override fun resetConnection() = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("reset_connection")
        Unit
    }

    fun attach(client: RetroArchNetworkClient) = backend.attach(client)

    fun detach(client: RetroArchNetworkClient) = backend.detach(client)

    override fun emulatorReattached() = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("bridge_reconnected")
        Unit
    }

    override fun close() = synchronized(OfflineGenerator.runtimeLock) {
        runtime.callAttr("close")
        Unit
    }
}
