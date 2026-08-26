package eu.odran.archipelago

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import org.json.JSONObject

/** Unified library for rooms hosted by this app and rooms joined through invitations. */
class HostedRoomsActivity : CompanionActivity() {
    private enum class RoomFilter { ALL, HOSTED, JOINED }
    private enum class RoomSort(val label: String) {
        RECENT("Recent"),
        NAME("Player name"),
        STATUS("Server status"),
    }

    private class RoomViewHolder(
        val container: FrameLayout,
        val card: HostedRoomCardView,
        val empty: TextView,
    ) : RecyclerView.ViewHolder(container)

    private inner class RoomAdapter : RecyclerView.Adapter<RoomViewHolder>() {
        private var rooms = emptyList<HostedRoomCardModel>()
        private var emptyMessage = ""

        fun submit(updatedRooms: List<HostedRoomCardModel>, updatedEmptyMessage: String) {
            val previousRooms = rooms
            val previousEmptyMessage = emptyMessage
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previousRooms.size.coerceAtLeast(1)

                override fun getNewListSize(): Int = updatedRooms.size.coerceAtLeast(1)

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val previous = previousRooms.getOrNull(oldItemPosition)
                    val updated = updatedRooms.getOrNull(newItemPosition)
                    return if (previous == null || updated == null) {
                        previous == null && updated == null
                    } else {
                        previous.room.roomId == updated.room.roomId
                    }
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val previous = previousRooms.getOrNull(oldItemPosition)
                    val updated = updatedRooms.getOrNull(newItemPosition)
                    return if (previous == null || updated == null) {
                        previous == null && updated == null && previousEmptyMessage == updatedEmptyMessage
                    } else {
                        previous == updated
                    }
                }
            })
            rooms = updatedRooms
            emptyMessage = updatedEmptyMessage
            diff.dispatchUpdatesTo(this)
        }

        override fun getItemCount(): Int = rooms.size.coerceAtLeast(1)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoomViewHolder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setPadding(0, 0, 0, CompanionUi.dp(parent.context, 10))
            }
            val card = HostedRoomCardView(parent.context)
            val empty = TextView(parent.context).apply {
                visibility = View.GONE
                CompanionUi.styleMuted(this)
                setPadding(0, CompanionUi.dp(parent.context, 10), 0, CompanionUi.dp(parent.context, 10))
            }
            val childParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            container.addView(card, childParams)
            container.addView(empty, FrameLayout.LayoutParams(childParams))
            return RoomViewHolder(container, card, empty)
        }

        override fun onBindViewHolder(holder: RoomViewHolder, position: Int) {
            val model = rooms.getOrNull(position)
            if (model == null) {
                holder.card.visibility = View.GONE
                holder.empty.visibility = View.VISIBLE
                holder.empty.text = emptyMessage
            } else {
                holder.empty.visibility = View.GONE
                holder.card.visibility = View.VISIBLE
                holder.card.bind(model, roomCardCallbacks(model))
            }
        }
    }

    private lateinit var webHostClient: ArchipelagoWebHostClient
    private lateinit var status: CompanionStatusView
    private lateinit var roomsContainer: RecyclerView
    private lateinit var roomAdapter: RoomAdapter
    private lateinit var refreshButton: Button
    private lateinit var restoreButton: Button
    private lateinit var allFilterButton: Button
    private lateinit var hostedFilterButton: Button
    private lateinit var joinedFilterButton: Button
    private lateinit var roomSearchEditor: EditText
    private lateinit var sortButton: Button
    private var roomFilter = RoomFilter.ALL
    private var roomSort = RoomSort.RECENT
    private val refreshingRoomIds = mutableSetOf<String>()
    private val wakingRoomIds = mutableSetOf<String>()
    private val unavailableRoomIds = mutableSetOf<String>()
    @Volatile private var startingRoomRefreshGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webHostClient = ArchipelagoWebHostClient(this)
        roomFilter = savedInstanceState?.getString(STATE_ROOM_FILTER)
            ?.let { saved -> RoomFilter.entries.firstOrNull { it.name == saved } }
            ?: RoomFilter.ALL
        roomSort = savedInstanceState?.getString(STATE_ROOM_SORT)
            ?.let { saved -> RoomSort.entries.firstOrNull { it.name == saved } }
            ?: RoomSort.RECENT
        status = CompanionStatusView(this).apply {
            text = "Rooms cached on this device."
        }
        roomAdapter = RoomAdapter()
        roomsContainer = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HostedRoomsActivity)
            adapter = roomAdapter
            isNestedScrollingEnabled = true
            clipToPadding = false
            contentDescription = "Room library results"
        }
        refreshButton = Button(this).apply {
            text = "Refresh from archipelago.gg"
            CompanionUi.stylePrimary(this)
            setOnClickListener { refreshHostedRooms() }
        }
        restoreButton = Button(this).apply {
            CompanionUi.styleQuiet(this)
            setOnClickListener { confirmRestoreHostedRooms() }
        }
        allFilterButton = filterButton("All", RoomFilter.ALL)
        hostedFilterButton = filterButton("Hosted", RoomFilter.HOSTED)
        joinedFilterButton = filterButton("Joined", RoomFilter.JOINED)
        roomSearchEditor = EditText(this).apply {
            hint = "Search rooms or players"
            contentDescription = "Search rooms by player, game, room identifier, or port"
            setSingleLine(true)
            setText(savedInstanceState?.getString(STATE_ROOM_SEARCH).orEmpty())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(value: Editable?) {
                    if (::roomsContainer.isInitialized) renderHostedRooms(webHostClient.cachedRooms())
                }
            })
        }
        sortButton = Button(this).apply {
            CompanionUi.styleQuiet(this)
            setOnClickListener(::showSortMenu)
        }
        updateSortButton()

        val content = CompanionUi.screen(this).apply {
            addView(
                CompanionUi.pageTitle(
                    this@HostedRoomsActivity,
                    "Rooms",
                    "Choose an active room and manage rooms you host or join.",
                ),
                CompanionUi.fullWidth(),
            )
            addView(CompanionUi.card(this@HostedRoomsActivity, "Room library").apply {
                addView(LinearLayout(this@HostedRoomsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(allFilterButton, CompanionUi.weightedButtonParams(this@HostedRoomsActivity, 4))
                    addView(hostedFilterButton, CompanionUi.weightedButtonParams(this@HostedRoomsActivity, 4))
                    addView(joinedFilterButton, CompanionUi.weightedButtonParams(this@HostedRoomsActivity))
                }, CompanionUi.fullWidth())
                addView(roomSearchEditor, CompanionUi.insetTop(roomSearchEditor, this@HostedRoomsActivity, 8))
                addView(sortButton, CompanionUi.insetTop(sortButton, this@HostedRoomsActivity, 4))
                addView(status, CompanionUi.insetTop(status, this@HostedRoomsActivity, 8))
                addView(roomsContainer, CompanionUi.insetTop(roomsContainer, this@HostedRoomsActivity, 8))
            }, CompanionUi.cardParams(this@HostedRoomsActivity))
            addView(
                CompanionUi.card(
                    this@HostedRoomsActivity,
                    "Website hosting",
                    "Refresh hosted rooms or manage this app's private archipelago.gg session.",
                ).apply {
                    addView(refreshButton, matchWrapParams())
                    val websiteTools = LinearLayout(this@HostedRoomsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(restoreButton, CompanionUi.insetTop(restoreButton, this@HostedRoomsActivity, 4))
                        addView(Button(this@HostedRoomsActivity).apply {
                            text = "Sync website session"
                            CompanionUi.styleQuiet(this)
                            setOnClickListener { confirmWebsiteSessionSync() }
                        }, CompanionUi.insetTop(this, this@HostedRoomsActivity, 4))
                        addView(Button(this@HostedRoomsActivity).apply {
                            text = "Open website instance list"
                            CompanionUi.styleQuiet(this)
                            setOnClickListener {
                                openAuthenticatedWebUrl(
                                    "${ArchipelagoWebHostClient.BASE_URL}/user-content",
                                    "Hosted instances",
                                )
                            }
                        }, CompanionUi.insetTop(this, this@HostedRoomsActivity, 4))
                    }
                    addView(
                        CompanionUi.toggleButton(this@HostedRoomsActivity, "website tools", websiteTools),
                        CompanionUi.insetTop(websiteTools, this@HostedRoomsActivity, 4),
                    )
                    addView(websiteTools, CompanionUi.fullWidth())
                },
                CompanionUi.cardParams(this@HostedRoomsActivity),
            )
        }
        val scrollView = CompanionUi.scrollView(this, content)
        SystemBarInsets.apply(window, scrollView)
        setContentView(scrollView)
        val cachedRooms = webHostClient.cachedRooms()
        val requestedRoomId = intent.getStringExtra(EXTRA_OPEN_ROOM_ID)
        requestedRoomId?.let { roomId ->
            intent.removeExtra(EXTRA_OPEN_ROOM_ID)
            val selected = runCatching {
                RoomSessionRepository.select(this, roomId)
                    ?: cachedRooms.firstOrNull { it.roomId == roomId }?.let { room ->
                        RoomSessionRepository.activate(this, room)
                    }
            }.getOrNull()
            if (selected == null) {
                status.text = "The requested room is not available in the room library yet."
            }
        }
        renderHostedRooms(cachedRooms)
        requestedRoomId?.let(::refreshStartingRoom)
        intent.getStringExtra(EXTRA_SHARE_ROOM_ID)?.let { roomId ->
            intent.removeExtra(EXTRA_SHARE_ROOM_ID)
            cachedRooms.firstOrNull { it.roomId == roomId }?.let(::shareHostedRoom)
                ?: run { status.text = "The active room is not available in the hosted-room list yet." }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::roomsContainer.isInitialized) renderHostedRooms(webHostClient.cachedRooms())
    }

    override fun onDestroy() {
        startingRoomRefreshGeneration += 1
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ROOM_FILTER, roomFilter.name)
        outState.putString(STATE_ROOM_SORT, roomSort.name)
        outState.putString(STATE_ROOM_SEARCH, roomSearchEditor.text.toString())
    }

    private fun showSortMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            RoomSort.entries.forEach { sort -> menu.add(sort.label) }
            setOnMenuItemClickListener { item ->
                roomSort = RoomSort.entries.first { it.label == item.title.toString() }
                updateSortButton()
                renderHostedRooms(webHostClient.cachedRooms())
                true
            }
            show()
        }
    }

    private fun updateSortButton() {
        sortButton.text = "Sort: ${roomSort.label}"
        sortButton.contentDescription = "Room sorting. Current order: ${roomSort.label}"
    }

    private fun filterButton(label: String, filter: RoomFilter) = Button(this).apply {
        text = label
        setOnClickListener {
            roomFilter = filter
            renderHostedRooms(webHostClient.cachedRooms())
        }
    }

    private fun updateFilterButtons() {
        listOf(
            allFilterButton to RoomFilter.ALL,
            hostedFilterButton to RoomFilter.HOSTED,
            joinedFilterButton to RoomFilter.JOINED,
        ).forEach { (button, filter) ->
            if (roomFilter == filter) CompanionUi.stylePrimary(button) else CompanionUi.styleQuiet(button)
        }
    }

    private fun refreshHostedRooms() {
        refreshButton.isEnabled = false
        refreshingRoomIds += webHostClient.cachedRooms().map { it.roomId }
        renderHostedRooms(webHostClient.cachedRooms())
        status.text = "Refreshing hosted rooms from archipelago.gg…"
        thread(name = "archipelago-web-host-refresh") {
            runCatching { webHostClient.refreshRooms() }
                .onSuccess { rooms -> runOnUiThread {
                    refreshButton.isEnabled = true
                    refreshingRoomIds.clear()
                    unavailableRoomIds.removeAll(rooms.map { it.roomId }.toSet())
                    synchronizeActiveRoom(rooms)
                    renderHostedRooms(rooms)
                    status.text = if (rooms.isEmpty()) {
                        "No visible hosted rooms belong to this app's website session."
                    } else {
                        "Found ${rooms.size} hosted room${if (rooms.size == 1) "" else "s"}."
                    }
                } }
                .onFailure { error ->
                    runOnUiThread {
                        refreshButton.isEnabled = true
                        refreshingRoomIds.clear()
                        renderHostedRooms(webHostClient.cachedRooms())
                    }
                    showError("Could not refresh hosted rooms", error)
                }
        }
    }

    /** Wakes a just-created room and waits for archipelago.gg to publish its server port. */
    private fun refreshStartingRoom(roomId: String) {
        if (!ArchipelagoWebHostClient.ROOM_ID_PATTERN.matches(roomId)) return
        val generation = ++startingRoomRefreshGeneration
        wakingRoomIds += roomId
        renderHostedRooms(webHostClient.cachedRooms())
        status.text = "Room created · waiting for archipelago.gg to start its server…"
        thread(name = "starting-hosted-room-refresh") {
            runCatching { webHostClient.refreshPublicRoom(roomId) }
                .onSuccess { refreshed ->
                    if (generation != startingRoomRefreshGeneration) return@onSuccess
                    webHostClient.rememberRoom(refreshed)
                    runOnUiThread {
                        if (generation != startingRoomRefreshGeneration || isFinishing || isDestroyed) {
                            return@runOnUiThread
                        }
                        wakingRoomIds -= roomId
                        unavailableRoomIds -= roomId
                        synchronizeActiveRoom(listOf(refreshed))
                        renderHostedRooms(webHostClient.cachedRooms())
                        status.text = when {
                            refreshed.lastPort > 0 ->
                                "Room ready · archipelago.gg:${refreshed.lastPort}"
                            refreshed.lastPort < 0 ->
                                "The new room reported a server error. Open room controls for details."
                            else ->
                                "The room is still starting. Tap refresh if its port does not appear shortly."
                        }
                    }
                }
                .onFailure { error ->
                    if (generation == startingRoomRefreshGeneration) {
                        runOnUiThread {
                            wakingRoomIds -= roomId
                            unavailableRoomIds += roomId
                            renderHostedRooms(webHostClient.cachedRooms())
                        }
                        showError("Could not refresh the new room", error)
                    }
                }
        }
    }

    private fun synchronizeActiveRoom(rooms: List<HostedRoom>) {
        val activeRoomId = RoomSessionRepository.activeRoom(this)?.roomId ?: return
        val refreshed = rooms.firstOrNull { it.roomId == activeRoomId } ?: return
        RoomSessionRepository.synchronizeActive(this, refreshed)
    }

    private fun renderHostedRooms(rooms: List<HostedRoom>) {
        updateFilterButtons()
        updateRestoreButton()
        val hostedById = rooms.associateBy { it.roomId }
        val joinedById = RoomSessionRepository.rooms(this).associateBy { it.roomId }
        val roomIds = when (roomFilter) {
            RoomFilter.ALL -> (hostedById.keys + joinedById.keys).distinct()
            RoomFilter.HOSTED -> hostedById.keys.toList()
            RoomFilter.JOINED -> joinedById.keys.toList()
        }
        val activeRoomId = RoomSessionRepository.activeRoom(this)?.roomId
        val recencyIndex = roomIds.withIndex().associate { it.value to it.index }
        val query = roomSearchEditor.text.toString().trim()
        val roomComparator = when (roomSort) {
            RoomSort.RECENT -> compareBy<HostedRoom> { recencyIndex[it.roomId] ?: Int.MAX_VALUE }
            RoomSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { room ->
                joinedById[room.roomId]?.playerName
                    ?: room.players.firstOrNull()?.let(::hostedPlayerName)
                    ?: room.roomId
            }
            RoomSort.STATUS -> compareBy<HostedRoom> { room ->
                when (roomStatusPresentation(
                    port = room.lastPort,
                    refreshing = room.roomId in refreshingRoomIds,
                    waking = room.roomId in wakingRoomIds,
                    available = room.roomId !in unavailableRoomIds,
                ).state) {
                    RoomRuntimeState.RUNNING -> 0
                    RoomRuntimeState.WAKING, RoomRuntimeState.REFRESHING -> 1
                    RoomRuntimeState.SLEEPING -> 2
                    RoomRuntimeState.UNAVAILABLE -> 3
                    RoomRuntimeState.ERROR -> 4
                }
            }.thenBy { it.roomId }
        }
        val visibleRooms = roomIds.mapNotNull { roomId ->
            hostedById[roomId] ?: joinedById[roomId]?.asHostedRoom()
        }.filter { room ->
            query.isBlank() || listOf(
                room.roomId,
                room.trackerId,
                room.lastPort.takeIf { it != 0 }?.toString().orEmpty(),
                joinedById[room.roomId]?.playerName.orEmpty(),
                joinedById[room.roomId]?.gameName.orEmpty(),
            ).plus(room.players).any { value -> value.contains(query, ignoreCase = true) }
        }.sortedWith(
            compareByDescending<HostedRoom> { it.roomId == activeRoomId }
                .then(roomComparator),
        )
        val historyById = SeedHistoryStore.list(this).associateBy { it.id }
        val models = visibleRooms.map { room ->
            val isActive = room.roomId == activeRoomId
            val isHosted = room.roomId in hostedById
            val joinedRoom = joinedById[room.roomId]
            val linkedEntry = HostedRoomHistoryLinks.historyId(this, room.roomId)?.let { linkedId ->
                historyById[linkedId]
            }
            val activePlayerChoices = activePlayerChoices(room, linkedEntry)
            val patchlessChoices = patchlessInviteChoices(room)
            val linkedSeedCanShareInvite = linkedEntry?.let(::inviteChoices)?.isNotEmpty()
            val sohPlayers = SohLauncher.players(room.players)
            val nativePlayerFiles = linkedEntry?.files
                ?.map { File(it.path) }
                ?.filter { it.isFile && PlayerFileLauncher.supports(it.name) }
                .orEmpty()
            val roomStatus = roomStatusPresentation(
                port = room.lastPort,
                refreshing = room.roomId in refreshingRoomIds,
                waking = room.roomId in wakingRoomIds,
                available = room.roomId !in unavailableRoomIds,
            )
            HostedRoomCardModel(
                room = room,
                title = joinedRoom?.playerName?.takeIf { it.isNotBlank() }
                    ?: room.players.firstOrNull()?.let(::hostedPlayerName)
                    ?: "Archipelago room",
                details = roomCardDetails(room, joinedRoom),
                isActive = isActive,
                isHosted = isHosted,
                joinedRoom = joinedRoom,
                activePlayerChoices = activePlayerChoices,
                patchlessChoices = patchlessChoices,
                linkedSeedCanShareInvite = linkedSeedCanShareInvite,
                sohPlayers = sohPlayers,
                nativePlayerFiles = nativePlayerFiles,
                status = roomStatus,
            )
        }
        val emptyMessage = if (query.isNotBlank()) {
            "No rooms match “$query”."
        } else when (roomFilter) {
            RoomFilter.ALL -> "No rooms yet. Open an invitation from Home or refresh website hosting."
            RoomFilter.HOSTED -> "No hosted rooms are cached. Refresh website hosting to look for them."
            RoomFilter.JOINED -> "No joined rooms yet. Open an invitation from Home to add one."
        }
        roomAdapter.submit(models, emptyMessage)
        updateRoomListHeight(models.size)
    }

    private fun roomCardDetails(room: HostedRoom, joinedRoom: JoinedRoom?): String = buildString {
        joinedRoom?.gameName?.takeIf { it.isNotBlank() }?.let { append(it) }
        if (room.lastPort > 0) {
            if (isNotEmpty()) append(" · ")
            append("archipelago.gg:${room.lastPort}")
        }
        if (room.players.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append(room.players.joinToString())
        }
        joinedRoom?.updatedAt?.takeIf { it > 0L }?.let {
            append("\n${formatStatusAge(it)}")
        }
        formatWebsiteTime(room.lastActivity).takeIf { it.isNotBlank() }?.let {
            append(" · Last activity $it")
        }
        if (isEmpty()) append("Room ${room.roomId.take(12)}…")
    }

    private fun roomCardCallbacks(model: HostedRoomCardModel) = HostedRoomCardCallbacks(
        onActivate = {
            if (model.isHosted) activateHostedRoom(model.room, model.activePlayerChoices)
            else model.joinedRoom?.let(::activateJoinedRoom)
        },
        onWakeOrRefresh = { wake -> refreshRoom(model.room, wake) },
        onChoosePlayer = { chooseActivePlayer(model.room, model.activePlayerChoices) },
        onLaunchSoh = { chooseSohPlayer(model.room, model.sohPlayers) },
        onOpenPlayerFile = { chooseNativePlayerFile(model.room, model.nativePlayerFiles) },
        onShare = { shareHostedRoom(model.room) },
        onMore = { anchor ->
            showRoomMenu(
                anchor,
                model.room,
                model.isHosted,
                model.joinedRoom,
                model.activePlayerChoices,
                model.sohPlayers,
                model.nativePlayerFiles,
            )
        },
    )

    private fun updateRoomListHeight(roomCount: Int) {
        val desiredDp = roomLibraryHeightDp(roomCount, resources.configuration.screenHeightDp)
        roomsContainer.layoutParams = (roomsContainer.layoutParams ?: LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            CompanionUi.dp(this, desiredDp),
        )).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = CompanionUi.dp(this@HostedRoomsActivity, desiredDp)
        }
        roomsContainer.requestLayout()
    }

    private fun JoinedRoom.asHostedRoom() = HostedRoom(
        roomId = roomId,
        seedId = "",
        creationTime = "",
        lastActivity = "",
        lastPort = port,
        timeoutSeconds = 0,
        trackerId = trackerId,
        players = players,
    )

    private fun activateJoinedRoom(room: JoinedRoom) {
        RoomSessionRepository.select(this, room.roomId)
        setResult(RESULT_OK)
        Toast.makeText(this, "${room.playerName ?: "Room"} is now active", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun chooseNativePlayerFile(room: HostedRoom, files: List<File>) {
        if (files.size == 1) {
            launchNativePlayerFile(room, files.single())
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose player file")
            .setItems(files.map { it.name }.toTypedArray()) { _, index ->
                launchNativePlayerFile(room, files[index])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchNativePlayerFile(room: HostedRoom, playerFile: File) {
        val serverAddress = room.lastPort.takeIf { it > 0 }?.let { "archipelago.gg:$it" }
        runCatching {
            PlayerFileLauncher.launch(
                this,
                playerFile,
                PlayerFileLaunchOptions(serverAddress = serverAddress, saveSlot = 0),
            )
        }.onSuccess {
            status.text = if (serverAddress == null) {
                "Opened ${playerFile.name}. The room has no current server port."
            } else {
                "Sent ${playerFile.name} and $serverAddress to the game."
            }
        }.onFailure { showError("Could not open ${playerFile.name}", it) }
    }

    private fun showRoomMenu(
        anchor: View,
        room: HostedRoom,
        isHosted: Boolean,
        joinedRoom: JoinedRoom?,
        activePlayerChoices: List<HostedInviteChoice>,
        sohPlayers: List<SohPlayer>,
        nativePlayerFiles: List<File>,
    ) {
        PopupMenu(this, anchor).apply {
            if (activePlayerChoices.isNotEmpty()) {
                menu.add(if (joinedRoom?.playerSlot == null) "Choose player" else "Change player")
            }
            if (sohPlayers.isNotEmpty()) menu.add("Launch Ship of Harkinian")
            if (nativePlayerFiles.isNotEmpty()) menu.add("Open player file")
            menu.add(if (room.lastPort > 0) "Refresh room status" else "Wake and refresh room")
            menu.add("Open room controls")
            if (room.trackerId.isNotBlank()) menu.add("Open tracker")
            if (room.lastPort > 0) menu.add("Copy server address")
            menu.add(if (isHosted) "Remove from this app" else "Forget joined room")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Choose player", "Change player" -> chooseActivePlayer(room, activePlayerChoices)
                    "Launch Ship of Harkinian" -> chooseSohPlayer(room, sohPlayers)
                    "Open player file" -> chooseNativePlayerFile(room, nativePlayerFiles)
                    "Refresh room status", "Wake and refresh room" ->
                        refreshRoom(room, wake = room.lastPort <= 0)
                    "Open room controls" -> if (isHosted) {
                        openAuthenticatedWebUrl(
                            "${ArchipelagoWebHostClient.BASE_URL}/room/${room.roomId}",
                            "Room controls",
                        )
                    } else {
                        openWebUrl("${ArchipelagoWebHostClient.BASE_URL}/room/${room.roomId}")
                    }
                    "Open tracker" -> openWebUrl(
                        "${ArchipelagoWebHostClient.BASE_URL}/tracker/${room.trackerId}",
                    )
                    "Copy server address" -> {
                        val serverAddress = "archipelago.gg:${room.lastPort}"
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Archipelago server", serverAddress))
                        Toast.makeText(this@HostedRoomsActivity, "Server address copied", Toast.LENGTH_SHORT).show()
                    }
                    "Remove from this app" -> confirmDismissHostedRoom(room)
                    "Forget joined room" -> joinedRoom?.let(::confirmForgetJoinedRoom)
                }
                true
            }
            show()
        }
    }

    private fun refreshRoom(room: HostedRoom, wake: Boolean) {
        if (room.roomId in refreshingRoomIds || room.roomId in wakingRoomIds) return
        unavailableRoomIds -= room.roomId
        if (wake) wakingRoomIds += room.roomId else refreshingRoomIds += room.roomId
        status.show(
            if (wake) "Waking ${room.players.firstOrNull()?.let(::hostedPlayerName) ?: "room"}…"
            else "Refreshing room status and port…",
            CompanionStatusLevel.INFO,
        )
        renderHostedRooms(webHostClient.cachedRooms())
        thread(name = "hosted-room-status-refresh") {
            runCatching { webHostClient.refreshPublicRoom(room.roomId) }
                .onSuccess { refreshed ->
                    webHostClient.rememberRoom(refreshed)
                    val activeRefresh = RoomSessionRepository.synchronizeActive(this, refreshed)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        refreshingRoomIds -= room.roomId
                        wakingRoomIds -= room.roomId
                        unavailableRoomIds -= room.roomId
                        renderHostedRooms(webHostClient.cachedRooms())
                        val presentation = roomStatusPresentation(refreshed.lastPort)
                        status.show(presentation.summary, presentation.level)
                        if (activeRefresh?.addressChanged == true && activeRefresh.updatedAddress != null) {
                            startForegroundService(
                                Intent(this, BridgeService::class.java)
                                    .setAction(BridgeService.ACTION_RECONNECT),
                            )
                        }
                    }
                }
                .onFailure { error -> runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshingRoomIds -= room.roomId
                    wakingRoomIds -= room.roomId
                    unavailableRoomIds += room.roomId
                    renderHostedRooms(webHostClient.cachedRooms())
                    showError("Could not refresh room", error)
                } }
        }
    }

    private fun confirmForgetJoinedRoom(room: JoinedRoom) {
        AlertDialog.Builder(this)
            .setTitle("Forget joined room?")
            .setMessage("This removes the invitation and room shortcut from this device. It does not affect the room on archipelago.gg.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ ->
                RoomSessionRepository.remove(this, room.roomId)
                setResult(RESULT_OK)
                renderHostedRooms(webHostClient.cachedRooms())
                status.text = "Joined room removed from this device."
            }
            .show()
    }

    private fun activateHostedRoom(room: HostedRoom, choices: List<HostedInviteChoice>) {
        val previous = RoomSessionRepository.rooms(this).firstOrNull { it.roomId == room.roomId }
        if (previous?.playerSlot == null && choices.isNotEmpty()) {
            chooseActivePlayer(room, choices)
            return
        }
        completeHostedRoomActivation(room)
    }

    private fun chooseActivePlayer(room: HostedRoom, choices: List<HostedInviteChoice>) {
        if (choices.isEmpty()) {
            status.text = "No player metadata is available for this room."
            return
        }
        fun select(choice: HostedInviteChoice) {
            val invite = RoomInvite(
                roomId = room.roomId,
                seedId = room.seedId,
                playerSlot = choice.slot,
                playerName = choice.playerName,
                gameName = choice.game,
            )
            completeHostedRoomActivation(room, invite)
        }
        if (choices.size == 1) {
            select(choices.single())
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose the active player")
            .setItems(choices.map { choice ->
                buildString {
                    append("Slot ${choice.slot} · ${choice.playerName}")
                    choice.game.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
            }.toTypedArray()) { _, index -> select(choices[index]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun completeHostedRoomActivation(room: HostedRoom, invite: RoomInvite? = null) {
        val joined = RoomSessionRepository.activate(this, room, invite)
        webHostClient.rememberRoom(room)
        setResult(RESULT_OK)
        Toast.makeText(
            this,
            "${joined.playerName ?: "Room"} is now active",
            Toast.LENGTH_SHORT,
        ).show()
        finish()
    }

    private fun confirmDismissHostedRoom(room: HostedRoom) {
        AlertDialog.Builder(this)
            .setTitle("Remove hosted room from this app?")
            .setMessage(buildString {
                append("This clears the room's local hosted listing, saved-room shortcut, and seed link.")
                if (room.players.isNotEmpty()) append("\n\n${room.players.joinToString()}")
                append(
                    "\n\nIt does not stop or delete the room or seed on archipelago.gg. " +
                        "It remains hidden after refresh until you restore removed rooms.",
                )
            })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove locally") { _, _ ->
                val visibleRooms = webHostClient.dismissRoom(room.roomId)
                HostedRoomHistoryLinks.remove(this, room.roomId)
                RoomSessionRepository.remove(this, room.roomId)
                renderHostedRooms(visibleRooms)
                status.text = "Removed locally. The archipelago.gg room was not deleted."
            }
            .show()
    }

    private fun confirmRestoreHostedRooms() {
        val count = webHostClient.dismissedRoomCount()
        if (count == 0) return
        AlertDialog.Builder(this)
            .setTitle("Restore removed hosted rooms?")
            .setMessage(
                "Show the $count locally removed room${if (count == 1) "" else "s"} again if they still exist " +
                    "in this app's archipelago.gg website session.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore and refresh") { _, _ ->
                webHostClient.restoreDismissedRooms()
                updateRestoreButton()
                refreshHostedRooms()
            }
            .show()
    }

    private fun updateRestoreButton() {
        val count = webHostClient.dismissedRoomCount()
        restoreButton.visibility = if (count > 0) View.VISIBLE else View.GONE
        restoreButton.text = "Restore $count removed room${if (count == 1) "" else "s"}"
    }

    private fun chooseSohPlayer(room: HostedRoom, players: List<SohPlayer>) {
        if (room.lastPort <= 0) {
            status.text = "Refresh hosted rooms after the server has started."
            return
        }
        fun launch(player: SohPlayer) {
            val serverAddress = "archipelago.gg:${room.lastPort}"
            SohLauncher.promptAndLaunch(
                this,
                serverAddress,
                player.name,
                onLaunched = {
                    RoomSessionRepository.activate(
                        this,
                        room,
                        RoomInvite(
                            roomId = room.roomId,
                            seedId = room.seedId,
                            playerSlot = player.slot,
                            playerName = player.name,
                            gameName = SohLauncher.GAME_NAME,
                        ),
                    )
                    status.text = "Launching Ship of Harkinian as ${player.name} at $serverAddress…"
                },
                onFailure = {
                    status.text = "Could not launch Ship of Harkinian. Check that the Archipelago-enabled app is installed."
                },
            )
        }
        if (players.size == 1) {
            launch(players.single())
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Launch which SoH player?")
            .setItems(players.map { "Slot ${it.slot} · ${it.name}" }.toTypedArray()) { _, index ->
                launch(players[index])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareHostedRoom(room: HostedRoom) {
        val entries = SeedHistoryStore.list(this).filter { inviteChoices(it).isNotEmpty() }
        if (entries.isEmpty()) {
            val patchlessChoices = patchlessInviteChoices(room)
            if (patchlessChoices.isNotEmpty()) chooseInvitePlayer(room, patchlessChoices)
            else status.text = "No locally stored seed with shareable players is available for this room."
            return
        }
        val linkedEntry = HostedRoomHistoryLinks.historyId(this, room.roomId)?.let { linkedId ->
            entries.firstOrNull { it.id == linkedId }
        }
        if (linkedEntry != null) {
            chooseInvitePlayer(room, inviteChoices(linkedEntry))
            return
        }
        val hostedNames = room.players.map(::hostedPlayerName)
        val matchingEntries = entries.filter { it.players == hostedNames }
        val patchlessChoices = patchlessInviteChoices(room)
        when {
            matchingEntries.size == 1 -> {
                HostedRoomHistoryLinks.save(this, room.roomId, matchingEntries.single().id)
                chooseInvitePlayer(room, inviteChoices(matchingEntries.single()))
            }
            matchingEntries.isEmpty() && patchlessChoices.isNotEmpty() -> chooseInvitePlayer(room, patchlessChoices)
            else -> chooseInviteSeed(room, matchingEntries.ifEmpty { entries })
        }
    }

    private fun chooseInviteSeed(room: HostedRoom, entries: List<SeedHistoryEntry>) {
        val labels = entries.map { entry ->
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(entry.createdAt))
            "Seed ${entry.seed} · ${entry.players.joinToString()} · $date"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose the local seed for this room")
            .setMessage("The seed supplies the player's game and, when required, the embedded patch.")
            .setNegativeButton("Cancel", null)
            .setItems(labels) { _, index ->
                val entry = entries[index]
                HostedRoomHistoryLinks.save(this, room.roomId, entry.id)
                chooseInvitePlayer(room, inviteChoices(entry))
            }
            .show()
    }

    private fun chooseInvitePlayer(room: HostedRoom, choices: List<HostedInviteChoice>) {
        if (choices.isEmpty()) {
            status.text = "The selected seed has no shareable player invites."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Share invite for which player?")
            .setItems(choices.map { choice ->
                buildString {
                    append("Player ${choice.slot} · ${choice.playerName} · ${choice.game}")
                    when {
                        choice.patch?.let { PlayerFileLauncher.supports(it.name) } == true ->
                            append(" · native player file included")
                        choice.patch == null -> append(" · no patch needed")
                    }
                }
            }.toTypedArray()) { _, index ->
                val choice = choices[index]
                status.text = "Preparing ${choice.playerName}'s multiplayer invitation…"
                val patch = choice.patch
                if (patch == null) {
                    runCatching {
                        RoomInvite.sharePatchless(this, room, choice.slot, choice.playerName, choice.game)
                    }.onSuccess {
                        status.text = "Patchless player invitation ready for ${choice.playerName}."
                    }.onFailure { showError("Could not share player invitation", it) }
                } else {
                    thread(name = "player-invite-package") {
                        runCatching { patch.readBytes() }
                            .onSuccess { patchBytes -> runOnUiThread {
                                runCatching {
                                    RoomInvite.share(
                                        this,
                                        room,
                                        choice.slot,
                                        choice.playerName,
                                        patch.name,
                                        patchBytes,
                                    )
                                }.onSuccess {
                                    status.text = if (PlayerFileLauncher.supports(patch.name)) {
                                        "Invitation with ${patch.name} ready for ${choice.playerName}."
                                    } else {
                                        "Player-specific invitation ready for ${choice.playerName}."
                                    }
                                }.onFailure { showError("Could not share player invitation", it) }
                            } }
                            .onFailure { showError("Could not read ${patch.name}", it) }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun inviteChoices(entry: SeedHistoryEntry): List<HostedInviteChoice> {
        val playerGames = playerGamesFromYaml(entry.yaml)
        val playerFiles = (
            entry.patches + entry.files.filter { PlayerFileLauncher.supports(it.name) }
        ).distinctBy { it.path }
        val patchesBySlot = playerFiles.mapIndexedNotNull { index, playerFile ->
            File(playerFile.path).takeIf { it.isFile }?.let { file ->
                playerSlotFromPatchName(playerFile.name, index) to file
            }
        }.toMap()
        val catalog = OfflineGenerator.cachedCatalog().associateBy { it.game }
        return entry.players.mapIndexedNotNull { index, playerName ->
            val slot = index + 1
            val patch = patchesBySlot[slot]
            val game = playerGames.getOrNull(index)
                ?: patch?.let(::gameFromPatchFile)
                ?: return@mapIndexedNotNull null
            if (PlayerFileLauncher.handlerForGame(game) != null && patch == null) {
                return@mapIndexedNotNull null
            }
            if (patch == null && !SohLauncher.isGame(game) && catalog[game]?.romPatch != false) {
                return@mapIndexedNotNull null
            }
            HostedInviteChoice(slot, playerName, game, patch)
        }
    }

    private fun patchlessInviteChoices(room: HostedRoom): List<HostedInviteChoice> {
        val catalog = OfflineGenerator.cachedCatalog().associateBy { it.game }
        return room.players.mapIndexedNotNull { index, displayName ->
            val game = hostedPlayerGame(displayName) ?: return@mapIndexedNotNull null
            if (PlayerFileLauncher.handlerForGame(game) != null) return@mapIndexedNotNull null
            if (!SohLauncher.isGame(game) && catalog[game]?.romPatch != false) return@mapIndexedNotNull null
            HostedInviteChoice(index + 1, hostedPlayerName(displayName), game, null)
        }
    }

    private fun activePlayerChoices(
        room: HostedRoom,
        linkedEntry: SeedHistoryEntry?,
    ): List<HostedInviteChoice> {
        val linkedChoices = linkedEntry?.let(::inviteChoices).orEmpty()
        val roomChoices = room.players.mapIndexedNotNull { index, displayName ->
            val playerName = hostedPlayerName(displayName).takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            HostedInviteChoice(
                slot = index + 1,
                playerName = playerName,
                game = hostedPlayerGame(displayName).orEmpty(),
                patch = null,
            )
        }
        return (linkedChoices + roomChoices).distinctBy { it.slot }
    }

    private fun playerGamesFromYaml(yaml: String): List<String> =
        Regex("(?m)^game\\s*:\\s*(.+?)\\s*$")
            .findAll(yaml)
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.removeSurrounding("'")
                    ?.takeIf { it.isNotBlank() }
            }
            .toList()

    private fun playerSlotFromPatchName(name: String, fallbackIndex: Int): Int =
        Regex("(?:^|_)P(\\d+)(?:_|\\.)", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: (fallbackIndex + 1)

    private fun gameFromPatchFile(patch: File): String? = runCatching {
        if (PlayerFileLauncher.supports(patch.name)) {
            return@runCatching PlayerFileLauncher.declaredGame(patch.name, patch.readBytes())
        }
        ZipFile(patch).use { archive ->
            val manifest = archive.getEntry("archipelago.json") ?: return@use null
            archive.getInputStream(manifest).bufferedReader(Charsets.UTF_8).use { reader ->
                JSONObject(reader.readText()).optString("game").takeIf { it.isNotBlank() }
            }
        }
    }.getOrNull()

    private fun hostedPlayerName(displayName: String): String =
        displayName.replace(Regex(" \\([^()]+\\)$"), "")

    private fun hostedPlayerGame(displayName: String): String? =
        Regex(" \\(([^()]*)\\)$").find(displayName.trim())
            ?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun confirmWebsiteSessionSync() {
        AlertDialog.Builder(this)
            .setTitle("Sync archipelago.gg website session?")
            .setMessage(
                "This opens the companion's secret session link in your browser. Anyone who obtains that link " +
                    "can manage the session's seeds and rooms, so do not share it.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open secret link") { _, _ -> openWebUrl(webHostClient.sessionSyncUrl()) }
            .show()
    }

    private fun openWebUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun openAuthenticatedWebUrl(url: String, title: String) {
        startActivity(AuthenticatedWebActivity.intent(this, url, title))
    }

    private fun formatWebsiteTime(value: String): String {
        if (value.isBlank() || value == "null") return ""
        val parsed = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).parse(value)
        }.getOrNull()
        return parsed?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it)
        } ?: value
    }

    private fun showError(prefix: String, error: Throwable) {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.isNotBlank() }
            ?: error.javaClass.simpleName
        runOnUiThread { status.text = "$prefix:\n$message" }
    }

    private fun matchWrapParams() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private const val EXTRA_OPEN_ROOM_ID = "open_room_id"
        private const val EXTRA_SHARE_ROOM_ID = "share_room_id"
        private const val STATE_ROOM_FILTER = "room_filter"
        private const val STATE_ROOM_SORT = "room_sort"
        private const val STATE_ROOM_SEARCH = "room_search"

        fun openRoomIntent(context: Context, roomId: String) =
            Intent(context, HostedRoomsActivity::class.java).putExtra(EXTRA_OPEN_ROOM_ID, roomId)

        fun shareIntent(context: Context, roomId: String) =
            Intent(context, HostedRoomsActivity::class.java).putExtra(EXTRA_SHARE_ROOM_ID, roomId)
    }
}
