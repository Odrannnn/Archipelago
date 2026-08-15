package gg.archipelago.android

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * First Archipelago network slice: WebSocket handshake and ROM-token login.
 * Gameplay packets are observed but intentionally not applied yet.
 */
class ArchipelagoSession(
    private val settings: ServerSettings,
    private val romInfo: MetroidFusionProfile.RomInfo,
    private val onStatus: (String) -> Unit,
) : WebSocketListener() {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var socket: WebSocket? = null
    private var currentAddress: String = settings.address
    private var triedSecureFallback = false

    @Volatile
    var isClosed: Boolean = false
        private set

    fun connect() {
        isClosed = false
        triedSecureFallback = settings.address.startsWith("wss://", ignoreCase = true)
        open(settings.address)
    }

    private fun open(address: String) {
        currentAddress = address
        onStatus("Connecting to Archipelago at $address…")
        try {
            socket = client.newWebSocket(Request.Builder().url(address).build(), this)
        } catch (error: Exception) {
            isClosed = true
            onStatus("Invalid Archipelago server address · ${error.message}")
        }
    }

    fun close() {
        isClosed = true
        socket?.close(1000, "Companion reconnecting")
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        onStatus("Archipelago transport connected · waiting for room information…")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            processPackets(webSocket, JSONArray(text))
        } catch (error: Exception) {
            onStatus("Invalid Archipelago packet · ${error.message}")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        onMessage(webSocket, bytes.utf8())
    }

    override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
        if (!isClosed &&
            !triedSecureFallback &&
            currentAddress.startsWith("ws://", ignoreCase = true)
        ) {
            triedSecureFallback = true
            val secureAddress = "wss://${currentAddress.substringAfter("://")}"
            onStatus("Cleartext room connection ended · retrying securely…")
            open(secureAddress)
            return
        }
        isClosed = true
        onStatus("Archipelago disconnected · ${error.message ?: error.javaClass.simpleName}")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        isClosed = true
    }

    private fun processPackets(webSocket: WebSocket, packets: JSONArray) {
        for (index in 0 until packets.length()) {
            val packet = packets.getJSONObject(index)
            when (packet.getString("cmd")) {
                "RoomInfo" -> {
                    val version = packet.optJSONObject("version")
                    onStatus(
                        "Room found · server ${version?.optInt("major", 0)}.${version?.optInt("minor", 0)}.${version?.optInt("build", 0)} · authenticating…",
                    )
                    webSocket.send(JSONArray().put(connectPacket()).toString())
                }
                "Connected" -> {
                    val team = packet.getInt("team") + 1
                    val slot = packet.getInt("slot")
                    val missing = packet.optJSONArray("missing_locations")?.length() ?: 0
                    onStatus("Archipelago authenticated · team $team · slot $slot · $missing locations remaining")
                }
                "ConnectionRefused" -> {
                    val errors = packet.optJSONArray("errors") ?: JSONArray()
                    onStatus("Archipelago login refused · ${jsonArrayText(errors)}")
                }
                "ReceivedItems" -> {
                    val count = packet.optJSONArray("items")?.length() ?: 0
                    onStatus("Archipelago authenticated · observed $count queued item(s); delivery is disabled in this handshake build")
                }
            }
        }
    }

    private fun connectPacket(): JSONObject = JSONObject()
        .put("cmd", "Connect")
        .put("password", settings.password.ifBlank { JSONObject.NULL })
        .put("name", romInfo.auth)
        .put(
            "version",
            JSONObject()
                .put("major", 0)
                .put("minor", 6)
                .put("build", 8)
                .put("class", "Version"),
        )
        .put("tags", JSONArray().put("AP"))
        .put("items_handling", 0)
        .put("uuid", settings.clientId)
        .put("game", "Metroid Fusion")
        .put("slot_data", true)

    private fun jsonArrayText(values: JSONArray): String =
        (0 until values.length()).joinToString(", ") { values.optString(it) }
}
