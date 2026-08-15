package gg.archipelago.android

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
    private val romInfo: MetroidFusionProfile.RomInfo,
    private val onStatus: (String) -> Unit,
) : WebSocketListener() {
    private data class NetworkItem(val item: Long, val location: Long, val player: Int)
    private data class TickState(
        val authenticated: Boolean,
        val slot: Int,
        val items: List<NetworkItem>,
        val itemsKnown: Boolean,
        val itemNames: Map<Long, String>,
        val locationIds: Map<String, Long>,
        val slotData: MetroidFusionProfile.Companion.SlotData?,
    )

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val stateLock = Any()
    private val receivedItems = mutableListOf<NetworkItem>()
    private val itemNamesById = mutableMapOf<Long, String>()
    private val locationIdsByName = mutableMapOf<String, Long>()
    private val reportedLocations = mutableSetOf<Long>()
    private var receivedItemsKnown = false
    private var authenticated = false
    private var slot = -1
    private var slotData: MetroidFusionProfile.Companion.SlotData? = null
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

    /** Runs one gameplay synchronization pass on BridgeService's worker. */
    fun tick(profile: MetroidFusionProfile) {
        val snapshot = profile.snapshot() ?: return
        val state = synchronized(stateLock) {
            TickState(
                authenticated,
                slot,
                receivedItems.toList(),
                receivedItemsKnown,
                itemNamesById.toMap(),
                locationIdsByName.toMap(),
                slotData,
            )
        }
        if (!state.authenticated) return
        if (snapshot.hasReachedCredits) {
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
                    onStatus("Archipelago connected · waiting for Metroid Fusion game data…")
                    return
                }
                val suppliedByPatchedRom = item.player == state.slot && item.location >= 0
                if ((suppliedByPatchedRom || profile.applyRemoteItemWhileInGame(name, data)) &&
                    profile.setReceivedItemCountWhileInGame(receivedCount + 1)
                ) {
                    onStatus("Archipelago synchronized item ${receivedCount + 1}/${state.items.size} · $name")
                }
                return
            }
            receivedCount > state.items.size -> requestItemResync()
        }

        val now = System.currentTimeMillis()
        if (receivedCount == state.items.size && state.itemsKnown && now >= nextInventoryReconcileAt) {
            val receivedNames = state.items.map { item -> state.itemNames[item.item] ?: return }
            state.slotData?.let { data ->
                if (profile.reconcileInventoryWhileInGame(data.startInventory + receivedNames, data)) {
                    nextInventoryReconcileAt = now + TimeUnit.SECONDS.toMillis(3)
                }
            }
        }

        reportLocations(snapshot, state.locationIds)
    }

    private fun reportLocations(snapshot: MetroidFusionProfile.Snapshot, locationIds: Map<String, Long>) {
        val checked = MetroidFusionLocations.checkedNames(snapshot).mapNotNull(locationIds::get)
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
        onStatus("Archipelago transport connected · waiting for room information…")
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
        onStatus("Archipelago disconnected · ${error.message ?: error.javaClass.simpleName}")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (webSocket === socket) isClosed = true
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
                "RoomUpdate" -> receiveCheckedLocations(packet.optJSONArray("checked_locations"))
                "ConnectionRefused" -> {
                    val errors = packet.optJSONArray("errors") ?: JSONArray()
                    onStatus("Archipelago login refused · ${jsonArrayText(errors)}")
                }
                "ReceivedItems" -> receiveItems(packet)
            }
        }
    }

    private fun receiveDataPackage(packet: JSONObject) {
        val game = packet.optJSONObject("data")
            ?.optJSONObject("games")
            ?.optJSONObject(GAME)
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
            slotData = MetroidFusionProfile.Companion.SlotData(
                missileDataAmmo = data.optInt("MissileDataAmmo", 10),
                missileTankAmmo = data.optInt("MissileTankAmmo", 5),
                powerBombDataAmmo = data.optInt("PowerBombDataAmmo", 10),
                powerBombTankAmmo = data.optInt("PowerBombTankAmmo", 2),
                startInventory = jsonStringList(data.optJSONArray("StartInventory")),
            )
            reportedLocations.clear()
            receiveCheckedLocationsLocked(packet.optJSONArray("checked_locations"))
            goalReported = false
            syncRequested = false
            nextInventoryReconcileAt = 0
        }
        val team = packet.getInt("team") + 1
        val missing = packet.optJSONArray("missing_locations")?.length() ?: 0
        onStatus("Archipelago authenticated · team $team · slot $connectedSlot · $missing locations remaining")
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

    private fun receiveCheckedLocations(values: JSONArray?) {
        synchronized(stateLock) { receiveCheckedLocationsLocked(values) }
    }

    private fun jsonStringList(values: JSONArray?): List<String> =
        if (values == null) emptyList() else
            (0 until values.length()).map(values::getString)

    private fun receiveCheckedLocationsLocked(values: JSONArray?) {
        if (values == null) return
        for (index in 0 until values.length()) reportedLocations += values.getLong(index)
    }

    private fun sendPacket(packet: JSONObject): Boolean =
        socket?.send(JSONArray().put(packet).toString()) == true

    private fun dataPackagePacket(): JSONObject = JSONObject()
        .put("cmd", "GetDataPackage")
        .put("games", JSONArray().put(GAME))

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
        .put("items_handling", 0b011)
        .put("uuid", settings.clientId)
        .put("game", GAME)
        .put("slot_data", true)

    private fun jsonArrayText(values: JSONArray): String =
        (0 until values.length()).joinToString(", ") { values.optString(it) }

    companion object {
        private const val GAME = "Metroid Fusion"
        private const val CLIENT_GOAL = 30
    }
}
