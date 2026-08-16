package eu.odran.archipelago

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

/** Archipelago transport for an unmodified imported standard GBA client. */
class PythonArchipelagoSession(
    private val settings: ServerSettings,
    private val runtime: PythonGbaRuntime,
    private val romInfo: ImportedGbaRomInfo,
    private val onStatus: (String) -> Unit,
    private val onConnectionState: (ArchipelagoSession.ConnectionState, String?) -> Unit,
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
    private var currentAddress = settings.address
    private var triedSecureFallback = false
    private var lastRuntimeError = ""

    override val connectedSlot: Int?
        get() = slot

    override fun connect() {
        isClosed = false
        triedSecureFallback = settings.address.startsWith("wss://", ignoreCase = true)
        runtime.resetConnection()
        open(settings.address)
    }

    private fun open(address: String) {
        currentAddress = address
        val message = "Connecting ${romInfo.game} to Archipelago at $address…"
        onStatus(message)
        onConnectionState(ArchipelagoSession.ConnectionState.CONNECTING, message)
        try {
            socket = client.newWebSocket(Request.Builder().url(address).build(), this)
        } catch (error: Exception) {
            isClosed = true
            val messageError = "Invalid Archipelago server address · ${error.message}"
            onStatus(messageError)
            onConnectionState(ArchipelagoSession.ConnectionState.DISCONNECTED, messageError)
        }
    }

    override fun tick() {
        while (true) {
            val packet = packets.poll() ?: break
            runtime.processPacket(packet)
        }
        val result = runtime.tick()
        result.messages.forEach(::sendPacket)
        if (result.error.isNotBlank() && result.error != lastRuntimeError) {
            lastRuntimeError = result.error
            onStatus("${romInfo.game} client warning · ${result.error}")
        }
        if (result.disconnect) {
            onStatus("${romInfo.game} client rejected this room or ROM")
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
        onConnectionState(ArchipelagoSession.ConnectionState.DISCONNECTED, "Archipelago session closed")
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (webSocket !== socket) return
        val message = "Archipelago transport connected · waiting for room information…"
        onStatus(message)
        onConnectionState(ArchipelagoSession.ConnectionState.CONNECTING, message)
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
        when (packet.optString("cmd")) {
            "RoomInfo" -> {
                webSocket.send(JSONArray().put(dataPackagePacket()).put(connectPacket()).toString())
                onStatus("Room found · authenticating ${romInfo.auth} for ${romInfo.game}…")
            }
            "Connected" -> {
                slot = packet.getInt("slot")
                val missing = packet.optJSONArray("missing_locations")?.length() ?: 0
                val message = "Archipelago authenticated · ${romInfo.game} · slot $slot · $missing locations remaining"
                onStatus(message)
                onConnectionState(ArchipelagoSession.ConnectionState.CONNECTED, message)
            }
            "ConnectionRefused" -> {
                val errors = packet.optJSONArray("errors") ?: JSONArray()
                val message = "Archipelago login refused · " +
                    (0 until errors.length()).joinToString(", ") { errors.optString(it) }
                onStatus(message)
                onConnectionState(ArchipelagoSession.ConnectionState.DISCONNECTED, message)
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
        .put("tags", JSONArray().put("AP"))
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
        onConnectionState(ArchipelagoSession.ConnectionState.DISCONNECTED, message)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (webSocket !== socket) return
        isClosed = true
        slot = null
        onConnectionState(ArchipelagoSession.ConnectionState.DISCONNECTED, "Archipelago connection closed · code $code")
    }
}
