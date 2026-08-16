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
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream

/** Network callbacks queue state; [tick] owns all emulator memory access. */
class ArchipelagoSession(
    private val settings: ServerSettings,
    private val adapter: GameAdapter,
    private val romInfo: GameRomInfo,
    private val onStatus: (String) -> Unit,
    private val onConnectionState: (ConnectionState, String?) -> Unit,
) : WebSocketListener() {
    enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

    private data class NetworkItem(val item: Long, val location: Long, val player: Int)
    private data class TickState(
        val authenticated: Boolean,
        val slot: Int,
        val items: List<NetworkItem>,
        val itemsKnown: Boolean,
        val itemNames: Map<Long, String>,
        val playerNames: Map<Int, String>,
        val locationIds: Map<String, Long>,
        val slotData: GameSlotData?,
    )

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val stateLock = Any()
    private val receivedItems = mutableListOf<NetworkItem>()
    private val itemNamesById = mutableMapOf<Long, String>()
    private val playerNamesBySlot = mutableMapOf<Int, String>()
    private val locationIdsByName = mutableMapOf<String, Long>()
    private val reportedLocations = mutableSetOf<Long>()
    private var receivedItemsKnown = false
    private var authenticated = false
    private var slot = -1
    private var team = -1
    private var slotData: GameSlotData? = null
    private var goalReported = false
    private var syncRequested = false
    @Volatile private var nextInventoryReconcileAt = 0L

    @Volatile private var socket: WebSocket? = null
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
        val message = "Connecting to Archipelago at $address…"
        onStatus(message)
        onConnectionState(ConnectionState.CONNECTING, message)
        try {
            socket = client.newWebSocket(Request.Builder().url(address).build(), this)
        } catch (error: Exception) {
            isClosed = true
            val errorMessage = "Invalid Archipelago server address · ${error.message}"
            onStatus(errorMessage)
            onConnectionState(ConnectionState.DISCONNECTED, errorMessage)
        }
    }

    fun close() {
        isClosed = true
        socket?.close(1000, "Companion reconnecting")
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        onConnectionState(ConnectionState.DISCONNECTED, "Archipelago session closed")
    }

    /** Runs one gameplay synchronization pass on BridgeService's worker. */
    fun tick() {
        val snapshot = adapter.snapshot() ?: return
        val state = synchronized(stateLock) {
            TickState(
                authenticated,
                slot,
                receivedItems.toList(),
                receivedItemsKnown,
                itemNamesById.toMap(),
                playerNamesBySlot.toMap(),
                locationIdsByName.toMap(),
                slotData,
            )
        }
        if (!state.authenticated) return
        if (snapshot.hasReachedGoal) {
            reportGoal()
            return
        }

        val receivedCount = snapshot.receivedItemCount.let { if (it == 0xffff) 0 else it }
        when {
            receivedCount < state.items.size -> {
                val item = state.items[receivedCount]
                val name = state.itemNames[item.item]
                val data = state.slotData
                if (name == null || data == null) {
                    onStatus("Archipelago connected · waiting for ${adapter.gameName} game data…")
                    return
                }
                val suppliedByPatchedRom = item.player == state.slot && item.location >= 0
                if ((suppliedByPatchedRom || adapter.applyRemoteItemWhileInGame(name, data)) &&
                    adapter.setReceivedItemCountWhileInGame(receivedCount + 1)
                ) {
                    onStatus("Archipelago synchronized item ${receivedCount + 1}/${state.items.size} · $name")
                    if (!suppliedByPatchedRom) {
                        adapter.showPlayerMessage(itemNotification(name, item.player, state.slot, state.playerNames))
                    }
                }
                return
            }
            receivedCount > state.items.size -> requestItemResync()
        }

        val now = System.currentTimeMillis()
        if (receivedCount == state.items.size && state.itemsKnown && now >= nextInventoryReconcileAt) {
            val receivedNames = state.items.map { item -> state.itemNames[item.item] ?: return }
            state.slotData?.let { data ->
                if (adapter.reconcileInventoryWhileInGame(data.startInventory + receivedNames, data)) {
                    nextInventoryReconcileAt = now + TimeUnit.SECONDS.toMillis(3)
                }
            }
        }

        reportLocations(snapshot, state.locationIds)
    }

    private fun reportLocations(snapshot: GameSnapshot, locationIds: Map<String, Long>) {
        val checked = snapshot.checkedLocationNames.mapNotNull(locationIds::get)
        val unsent = synchronized(stateLock) { checked.filterNot(reportedLocations::contains) }
        if (unsent.isEmpty()) return
        if (sendPacket(JSONObject().put("cmd", "LocationChecks").put("locations", JSONArray(unsent)))) {
            val total = synchronized(stateLock) {
                reportedLocations.addAll(unsent)
                reportedLocations.size
            }
            onStatus("Archipelago synchronized $total checked location(s)")
        }
    }

    private fun reportGoal() {
        val shouldSend = synchronized(stateLock) { !goalReported }
        if (shouldSend && sendPacket(JSONObject().put("cmd", "StatusUpdate").put("status", CLIENT_GOAL))) {
            synchronized(stateLock) { goalReported = true }
            onStatus("Archipelago goal reported")
        }
    }

    private fun requestItemResync() {
        val shouldSend = synchronized(stateLock) {
            if (syncRequested) false else {
                syncRequested = true
                true
            }
        }
        if (shouldSend) sendPacket(JSONObject().put("cmd", "Sync"))
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (webSocket !== socket) return
        val message = "Archipelago transport connected · waiting for room information…"
        onStatus(message)
        onConnectionState(ConnectionState.CONNECTING, message)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (webSocket !== socket) return
        try {
            processPackets(webSocket, JSONArray(text))
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

    override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
        if (webSocket !== socket) return
        if (!isClosed && !triedSecureFallback && currentAddress.startsWith("ws://", ignoreCase = true)) {
            triedSecureFallback = true
            val secureAddress = "wss://${currentAddress.substringAfter("://")}"
            onStatus("Cleartext room connection ended · retrying securely…")
            open(secureAddress)
            return
        }
        isClosed = true
        val message = "Archipelago disconnected · ${error.message ?: error.javaClass.simpleName}"
        onStatus(message)
        onConnectionState(ConnectionState.DISCONNECTED, message)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (webSocket === socket) {
            isClosed = true
            onConnectionState(
                ConnectionState.DISCONNECTED,
                "Archipelago connection closed · code $code${reason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
            )
        }
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
                    webSocket.send(JSONArray().put(dataPackagePacket()).put(connectPacket()).toString())
                }
                "DataPackage" -> receiveDataPackage(packet)
                "Connected" -> receiveConnected(packet)
                "RoomUpdate" -> receiveRoomUpdate(packet)
                "ConnectionRefused" -> {
                    val errors = packet.optJSONArray("errors") ?: JSONArray()
                    val message = "Archipelago login refused · ${jsonArrayText(errors)}"
                    onStatus(message)
                    onConnectionState(ConnectionState.DISCONNECTED, message)
                }
                "ReceivedItems" -> receiveItems(packet)
            }
        }
    }

    private fun receiveDataPackage(packet: JSONObject) {
        val game = packet.optJSONObject("data")
            ?.optJSONObject("games")
            ?.optJSONObject(adapter.gameName)
            ?: return
        val itemMap = game.optJSONObject("item_name_to_id") ?: JSONObject()
        val locationMap = game.optJSONObject("location_name_to_id") ?: JSONObject()
        synchronized(stateLock) {
            itemNamesById.clear()
            itemMap.keys().forEach { name -> itemNamesById[itemMap.getLong(name)] = name }
            locationIdsByName.clear()
            locationMap.keys().forEach { name -> locationIdsByName[name] = locationMap.getLong(name) }
        }
    }

    private fun receiveConnected(packet: JSONObject) {
        val connectedSlot = packet.getInt("slot")
        val data = packet.optJSONObject("slot_data") ?: JSONObject()
        synchronized(stateLock) {
            authenticated = true
            slot = connectedSlot
            team = packet.getInt("team")
            playerNamesBySlot.clear()
            playerNamesBySlot[0] = "Archipelago"
            receivePlayersLocked(packet.optJSONArray("players"), team)
            slotData = adapter.parseSlotData(data)
            reportedLocations.clear()
            receiveCheckedLocationsLocked(packet.optJSONArray("checked_locations"))
            goalReported = false
            syncRequested = false
            nextInventoryReconcileAt = 0
        }
        val teamNumber = packet.getInt("team") + 1
        val missing = packet.optJSONArray("missing_locations")?.length() ?: 0
        val message =
            "Archipelago authenticated · team $teamNumber · slot $connectedSlot · $missing locations remaining"
        onStatus(message)
        onConnectionState(ConnectionState.CONNECTED, message)
    }

    private fun receiveItems(packet: JSONObject) {
        val index = packet.getInt("index")
        val items = packet.optJSONArray("items") ?: JSONArray()
        var mismatch = false
        synchronized(stateLock) {
            if (index == 0) {
                receivedItems.clear()
                receivedItemsKnown = true
            }
            if (index != receivedItems.size) {
                mismatch = true
            } else {
                for (itemIndex in 0 until items.length()) {
                    val item = items.getJSONObject(itemIndex)
                    receivedItems += NetworkItem(
                        item = item.getLong("item"),
                        location = item.getLong("location"),
                        player = item.getInt("player"),
                    )
                }
                syncRequested = false
            }
        }
        if (mismatch) requestItemResync()
    }

    private fun receiveRoomUpdate(packet: JSONObject) {
        synchronized(stateLock) {
            receiveCheckedLocationsLocked(packet.optJSONArray("checked_locations"))
            if (packet.has("players")) receivePlayersLocked(packet.optJSONArray("players"), team)
        }
    }

    private fun receivePlayersLocked(values: JSONArray?, currentTeam: Int) {
        if (values == null) return
        for (index in 0 until values.length()) {
            val player = values.getJSONObject(index)
            if (player.getInt("team") == currentTeam) {
                playerNamesBySlot[player.getInt("slot")] = player.optString("alias")
                    .ifBlank { player.optString("name", "Player ${player.getInt("slot")}") }
            }
        }
    }

    private fun receiveCheckedLocationsLocked(values: JSONArray?) {
        if (values == null) return
        for (index in 0 until values.length()) reportedLocations += values.getLong(index)
    }

    private fun sendPacket(packet: JSONObject): Boolean =
        socket?.send(JSONArray().put(packet).toString()) == true

    private fun dataPackagePacket(): JSONObject = JSONObject()
        .put("cmd", "GetDataPackage")
        .put("games", JSONArray().put(adapter.gameName))

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
        .put("items_handling", adapter.itemsHandling)
        .put("uuid", settings.clientId)
        .put("game", adapter.gameName)
        .put("slot_data", true)

    private fun jsonArrayText(values: JSONArray): String =
        (0 until values.length()).joinToString(", ") { values.optString(it) }

    private fun itemNotification(
        itemName: String,
        sourceSlot: Int,
        ownSlot: Int,
        playerNames: Map<Int, String>,
    ): String = if (sourceSlot == ownSlot) {
        "Received $itemName"
    } else {
        "Received $itemName from ${playerNames[sourceSlot] ?: "Player $sourceSlot"}"
    }

    companion object {
        private const val CLIENT_GOAL = 30
    }
}
