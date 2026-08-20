package eu.odran.archipelago

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream

/** Archipelago transport for a supported Python game client. */
class PythonArchipelagoSession(
    private val settings: ServerSettings,
    private val runtime: PythonGameRuntime,
    private val romInfo: DetectedGameInfo,
    private val onStatus: (String) -> Unit,
    private val onConnectionState: (RoomConnectionState, String?) -> Unit,
) : WebSocketListener(), RoomSession {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val packets = ConcurrentLinkedQueue<JSONObject>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var slot: Int? = null
    @Volatile override var isClosed = false
        private set
    @Volatile override var automaticRetryAllowed = true
        private set
    private var currentAddress = settings.address
    private var triedSecureFallback = false
    private var lastRuntimeError = ""
    private var lastRuntimeDiagnostic = ""
    @Volatile private var handshakeDeadline = Long.MAX_VALUE

    override val connectedSlot: Int?
        get() = slot

    override fun connect() {
        isClosed = false
        automaticRetryAllowed = true
        triedSecureFallback = settings.address.startsWith("wss://", ignoreCase = true)
        runtime.resetConnection()
        open(settings.address)
    }

    private fun open(address: String) {
        currentAddress = address
        handshakeDeadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        val message = "Connecting ${romInfo.game} to Archipelago at $address…"
        onStatus(message)
        onConnectionState(RoomConnectionState.CONNECTING, message)
        try {
            socket = client.newWebSocket(Request.Builder().url(address).build(), this)
        } catch (error: Exception) {
            isClosed = true
            val messageError = "Invalid Archipelago server address · ${error.message}"
            onStatus(messageError)
            onConnectionState(RoomConnectionState.DISCONNECTED, messageError)
        }
    }

    override fun tick(emulatorAvailable: Boolean) {
        if (!isClosed && slot == null && System.currentTimeMillis() >= handshakeDeadline) {
            val timedOutSocket = socket
            if (!triedSecureFallback && currentAddress.startsWith("ws://", ignoreCase = true)) {
                triedSecureFallback = true
                timedOutSocket?.cancel()
                open("wss://${currentAddress.substringAfter("://")}")
            } else {
                isClosed = true
                timedOutSocket?.cancel()
                socket = null
                val message = "Archipelago connection timed out before authentication"
                onStatus(message)
                onConnectionState(RoomConnectionState.DISCONNECTED, message)
            }
        }
        while (true) {
            val packet = packets.poll() ?: break
            val error = RoomPacketDispatcher.dispatch(runtime, packet)
            if (error != null) {
                val command = packet.optString("cmd", "unknown")
                val message = "${romInfo.game} ignored a game-handler error while processing $command · " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                Log.w(TAG, message, error)
                ClientConsoleStore.append("error", message)
                onStatus(message)
            }
        }
        val result = runtime.tick(emulatorAvailable)
        ClientConsoleStore.append(result.console)
        result.messages.forEach { packet ->
            val command = packet.optString("cmd")
            val sent = sendPacket(packet)
            if (command in DIAGNOSTIC_COMMANDS) {
                Log.i(DIAGNOSTIC_TAG, "Archipelago outgoing $command sent=$sent")
            }
            if (!sent) {
                Log.w(DIAGNOSTIC_TAG, "Archipelago outgoing $command could not be queued; reconnecting")
                socket?.cancel()
            }
        }
        if (result.error.isNotBlank() && result.error != lastRuntimeError) {
            lastRuntimeError = result.error
            onStatus("${romInfo.game} client warning · ${result.error}")
        }
        if (result.diagnostic.isNotBlank() && result.diagnostic != lastRuntimeDiagnostic) {
            lastRuntimeDiagnostic = result.diagnostic
            Log.i(DIAGNOSTIC_TAG, result.diagnostic)
        }
        if (result.disconnect) {
            onStatus("${romInfo.game} client rejected this room or ROM")
            automaticRetryAllowed = false
            close()
        }
    }

    override fun close() {
        isClosed = true
        slot = null
        socket?.close(1000, "Companion reconnecting")
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        runtime.resetConnection()
        onConnectionState(RoomConnectionState.DISCONNECTED, "Archipelago session closed")
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (webSocket !== socket) return
        val message = "Archipelago transport connected · waiting for room information…"
        onStatus(message)
        onConnectionState(RoomConnectionState.CONNECTING, message)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (webSocket !== socket) return
        try {
            val values = JSONArray(text)
            for (index in 0 until values.length()) receivePacket(webSocket, values.getJSONObject(index))
        } catch (error: Exception) {
            onStatus("Invalid Archipelago packet · ${error.message}")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        try {
            val payload = bytes.toByteArray()
            val text = if (payload.firstOrNull()?.toInt()?.toChar() == '[') {
                payload.decodeToString()
            } else {
                InflaterInputStream(ByteArrayInputStream(payload)).use { it.readBytes().decodeToString() }
            }
            onMessage(webSocket, text)
        } catch (error: Exception) {
            onStatus("Invalid compressed Archipelago packet · ${error.message}")
        }
    }

    private fun receivePacket(webSocket: WebSocket, packet: JSONObject) {
        if (packet.optString("cmd") == "ReceivedItems") {
            Log.i(
                DIAGNOSTIC_TAG,
                "Archipelago incoming ReceivedItems " +
                    "index=${packet.optInt("index", -1)} " +
                    "count=${packet.optJSONArray("items")?.length() ?: 0}",
            )
        }
        when (packet.optString("cmd")) {
            "RoomInfo" -> {
                webSocket.send(JSONArray().put(dataPackagePacket()).put(connectPacket()).toString())
                onStatus("Room found · authenticating ${romInfo.auth} for ${romInfo.game}…")
            }
            "Connected" -> {
                slot = packet.getInt("slot")
                handshakeDeadline = Long.MAX_VALUE
                val missing = packet.optJSONArray("missing_locations")?.length() ?: 0
                val message = "Archipelago authenticated · ${romInfo.game} · slot $slot · $missing locations remaining"
                onStatus(message)
                onConnectionState(RoomConnectionState.CONNECTED, message)
            }
            "ConnectionRefused" -> {
                val errors = packet.optJSONArray("errors") ?: JSONArray()
                val message = "Archipelago login refused · " +
                    (0 until errors.length()).joinToString(", ") { errors.optString(it) }
                onStatus(message)
                onConnectionState(RoomConnectionState.DISCONNECTED, message)
                automaticRetryAllowed = false
                isClosed = true
                webSocket.close(1000, "Login refused")
            }
        }
        packets.add(packet)
    }

    private fun sendPacket(packet: JSONObject): Boolean =
        socket?.send(JSONArray().put(packet).toString()) == true

    private fun dataPackagePacket(): JSONObject = JSONObject()
        .put("cmd", "GetDataPackage")
        .put("games", JSONArray().put(romInfo.game))

    private fun connectPacket(): JSONObject = JSONObject()
        .put("cmd", "Connect")
        .put("password", settings.password.ifBlank { JSONObject.NULL })
        .put("name", romInfo.auth)
        .put("version", JSONObject().put("major", 0).put("minor", 6).put("build", 8).put("class", "Version"))
        .put("tags", JSONArray().apply {
            put("AP")
            romInfo.tags.filterNot { it == "AP" }.forEach(::put)
        })
        .put("items_handling", romInfo.itemsHandling)
        .put("uuid", settings.clientId)
        .put("game", romInfo.game)
        .put("slot_data", romInfo.wantSlotData)

    override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
        if (webSocket !== socket) return
        if (!isClosed && !triedSecureFallback && currentAddress.startsWith("ws://", ignoreCase = true)) {
            triedSecureFallback = true
            open("wss://${currentAddress.substringAfter("://")}")
            return
        }
        isClosed = true
        val message = "Archipelago disconnected · ${error.message ?: error.javaClass.simpleName}"
        onStatus(message)
        onConnectionState(RoomConnectionState.DISCONNECTED, message)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (webSocket !== socket) return
        isClosed = true
        slot = null
        onConnectionState(RoomConnectionState.DISCONNECTED, "Archipelago connection closed · code $code")
    }

    companion object {
        private const val TAG = "ArchipelagoSession"
        private const val DIAGNOSTIC_TAG = "ItemRecovery"
        private val HANDSHAKE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15)
        private val DIAGNOSTIC_COMMANDS = setOf("Sync", "Connect", "LocationChecks", "StatusUpdate")
    }
}

internal object RoomPacketDispatcher {
    fun dispatch(runtime: PythonGameRuntime, packet: JSONObject): Exception? =
        guard { runtime.processPacket(packet) }

    fun guard(action: () -> Unit): Exception? =
        try {
            action()
            null
        } catch (error: Exception) {
            error
        }
}
