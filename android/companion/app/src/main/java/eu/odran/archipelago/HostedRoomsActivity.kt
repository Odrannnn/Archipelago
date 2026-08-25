package eu.odran.archipelago

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import org.json.JSONObject

private data class HostedInviteChoice(
    val slot: Int,
    val playerName: String,
    val game: String,
    val patch: File?,
)

/** Manages rooms owned by the companion's private archipelago.gg website session. */
class HostedRoomsActivity : Activity() {
    private lateinit var webHostClient: ArchipelagoWebHostClient
    private lateinit var status: TextView
    private lateinit var roomsContainer: LinearLayout
    private lateinit var refreshButton: Button
    private lateinit var restoreButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webHostClient = ArchipelagoWebHostClient(this)
        status = TextView(this).apply {
            text = "Hosted rooms cached on this device."
            CompanionUi.styleBody(this)
        }
        roomsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        refreshButton = Button(this).apply {
            text = "Refresh from archipelago.gg"
            CompanionUi.stylePrimary(this)
            setOnClickListener { refreshHostedRooms() }
        }
        restoreButton = Button(this).apply {
            CompanionUi.styleQuiet(this)
            setOnClickListener { confirmRestoreHostedRooms() }
        }

        val content = CompanionUi.screen(this).apply {
            addView(
                CompanionUi.pageTitle(
                    this@HostedRoomsActivity,
                    "Hosted rooms",
                    "Manage rooms owned by this app's private archipelago.gg website session.",
                ),
                CompanionUi.fullWidth(),
            )
            addView(CompanionUi.card(this@HostedRoomsActivity, "Status").apply {
                addView(status, CompanionUi.fullWidth())
            }, CompanionUi.cardParams(this@HostedRoomsActivity))
            addView(
                CompanionUi.card(
                    this@HostedRoomsActivity,
                    "Website session",
                    "Refresh rooms, synchronize this private session with a browser, or open its website controls.",
                ).apply {
                    addView(refreshButton, matchWrapParams())
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
                },
                CompanionUi.cardParams(this@HostedRoomsActivity),
            )
            addView(
                CompanionUi.card(
                    this@HostedRoomsActivity,
                    "Rooms",
                    "Removing a room here affects only this device; it does not stop or delete it on archipelago.gg.",
                ).apply {
                    addView(roomsContainer, matchWrapParams())
                },
                CompanionUi.cardParams(this@HostedRoomsActivity),
            )
        }
        val scrollView = ScrollView(this).apply { addView(content) }
        SystemBarInsets.apply(window, scrollView)
        setContentView(scrollView)
        val cachedRooms = webHostClient.cachedRooms()
        renderHostedRooms(cachedRooms)
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

    private fun refreshHostedRooms() {
        refreshButton.isEnabled = false
        status.text = "Refreshing hosted rooms from archipelago.gg…"
        thread(name = "archipelago-web-host-refresh") {
            runCatching { webHostClient.refreshRooms() }
                .onSuccess { rooms -> runOnUiThread {
                    refreshButton.isEnabled = true
                    renderHostedRooms(rooms)
                    status.text = if (rooms.isEmpty()) {
                        "No visible hosted rooms belong to this app's website session."
                    } else {
                        "Found ${rooms.size} hosted room${if (rooms.size == 1) "" else "s"}."
                    }
                } }
                .onFailure { error ->
                    runOnUiThread { refreshButton.isEnabled = true }
                    showError("Could not refresh hosted rooms", error)
                }
        }
    }

    private fun renderHostedRooms(rooms: List<HostedRoom>) {
        roomsContainer.removeAllViews()
        updateRestoreButton()
        if (rooms.isEmpty()) {
            roomsContainer.addView(TextView(this).apply {
                text = "No hosted rooms are cached on this device. Refresh to load them from archipelago.gg."
                CompanionUi.styleMuted(this)
            }, matchWrapParams())
            return
        }
        val activeRoomId = JoinedRoomStore.load(this)?.roomId
        rooms.forEachIndexed { index, room ->
            val isActive = room.roomId == activeRoomId
            val linkedEntry = HostedRoomHistoryLinks.historyId(this, room.roomId)?.let { linkedId ->
                SeedHistoryStore.list(this).firstOrNull { it.id == linkedId }
            }
            val patchlessChoices = patchlessInviteChoices(room)
            val linkedSeedCanShareInvite = linkedEntry?.let(::inviteChoices)?.isNotEmpty()
            val sohPlayers = SohLauncher.players(room.players)
            val nativePlayerFiles = linkedEntry?.files
                ?.map { File(it.path) }
                ?.filter { it.isFile && PlayerFileLauncher.supports(it.name) }
                .orEmpty()
            val panel = CompanionUi.panel(this, active = isActive).apply {
                addView(TextView(this@HostedRoomsActivity).apply {
                    text = when {
                        isActive && room.lastPort > 0 -> "Active · online at archipelago.gg:${room.lastPort}"
                        isActive -> "Active · server starting or sleeping"
                        room.lastPort > 0 -> "Online · archipelago.gg:${room.lastPort}"
                        room.lastPort < 0 -> "Server error"
                        else -> "Starting or sleeping"
                    }
                    textSize = 17f
                    setTextColor(if (isActive) CompanionUi.active else CompanionUi.text)
                }, matchWrapParams())
                addView(TextView(this@HostedRoomsActivity).apply {
                    text = buildString {
                        val created = formatWebsiteTime(room.creationTime)
                        if (created.isNotBlank()) append("Created $created")
                        if (room.players.isNotEmpty()) {
                            if (isNotEmpty()) append('\n')
                            append(room.players.joinToString())
                        }
                        if (isEmpty()) append("Room ${room.roomId.take(12)}…")
                    }
                    CompanionUi.styleMuted(this)
                }, CompanionUi.insetTop(this, this@HostedRoomsActivity, 4))
                addView(Button(this@HostedRoomsActivity).apply {
                    text = if (isActive) "Currently active" else "Load as active room"
                    isEnabled = !isActive
                    if (isActive) CompanionUi.styleQuiet(this) else CompanionUi.stylePrimary(this)
                    setOnClickListener { activateHostedRoom(room) }
                }, CompanionUi.insetTop(this, this@HostedRoomsActivity, 8))
                if (sohPlayers.isNotEmpty()) addView(Button(this@HostedRoomsActivity).apply {
                    text = if (room.lastPort > 0) "Launch Ship of Harkinian" else "SoH unavailable until server starts"
                    isEnabled = room.lastPort > 0
                    CompanionUi.stylePrimary(this)
                    setOnClickListener { chooseSohPlayer(room, sohPlayers) }
                }, CompanionUi.insetTop(this, this@HostedRoomsActivity, 8))
                nativePlayerFiles.forEach { playerFile ->
                    addView(Button(this@HostedRoomsActivity).apply {
                        text = PlayerFileLauncher.actionLabel(playerFile.name)
                        CompanionUi.stylePrimary(this)
                        setOnClickListener {
                            val serverAddress = room.lastPort.takeIf { it > 0 }
                                ?.let { "archipelago.gg:$it" }
                            runCatching {
                                PlayerFileLauncher.launch(
                                    this@HostedRoomsActivity,
                                    playerFile,
                                    PlayerFileLaunchOptions(serverAddress = serverAddress, saveSlot = 0),
                                )
                            }
                                .onSuccess {
                                    status.text = if (serverAddress == null) {
                                        "Opened ${playerFile.name} in LADXHD. The room has no current server port."
                                    } else {
                                        "Sent ${playerFile.name}, $serverAddress, and save position 1 to LADXHD."
                                    }
                                }
                                .onFailure { showError("Could not open ${playerFile.name}", it) }
                        }
                    }, CompanionUi.insetTop(this, this@HostedRoomsActivity, 8))
                }
                addView(Button(this@HostedRoomsActivity).apply {
                    text = if (linkedSeedCanShareInvite == false && patchlessChoices.isEmpty()) {
                        "Player invite unavailable"
                    } else {
                        "Share multiplayer invite"
                    }
                    isEnabled = linkedSeedCanShareInvite != false || patchlessChoices.isNotEmpty()
                    CompanionUi.styleSecondary(this)
                    setOnClickListener { shareHostedRoom(room) }
                }, CompanionUi.insetTop(this, this@HostedRoomsActivity, 4))
                addView(Button(this@HostedRoomsActivity).apply {
                    text = "Open room controls"
                    CompanionUi.styleQuiet(this)
                    setOnClickListener {
                        openAuthenticatedWebUrl(
                            "${ArchipelagoWebHostClient.BASE_URL}/room/${room.roomId}",
                            "Room controls",
                        )
                    }
                }, CompanionUi.insetTop(this, this@HostedRoomsActivity, 4))
                if (room.trackerId.isNotBlank()) addView(Button(this@HostedRoomsActivity).apply {
                    text = "Open tracker"
                    CompanionUi.styleQuiet(this)
                    setOnClickListener {
                        openWebUrl("${ArchipelagoWebHostClient.BASE_URL}/tracker/${room.trackerId}")
                    }
                }, CompanionUi.insetTop(this, this@HostedRoomsActivity, 4))
                if (room.lastPort > 0) addView(Button(this@HostedRoomsActivity).apply {
                    text = "Copy server address"
                    CompanionUi.styleQuiet(this)
                    setOnClickListener {
                        val serverAddress = "archipelago.gg:${room.lastPort}"
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Archipelago server", serverAddress))
                        status.text = "Copied $serverAddress"
                    }
                }, CompanionUi.insetTop(this, this@HostedRoomsActivity, 4))
                addView(Button(this@HostedRoomsActivity).apply {
                    text = "Remove from this app"
                    CompanionUi.styleDanger(this)
                    setOnClickListener { confirmDismissHostedRoom(room) }
                }, CompanionUi.insetTop(this, this@HostedRoomsActivity, 4))
            }
            roomsContainer.addView(
                panel,
                CompanionUi.insetTop(panel, this, if (index == 0) 0 else 10),
            )
        }
    }

    private fun activateHostedRoom(room: HostedRoom) {
        val joined = JoinedRoomStore.save(this, room)
        webHostClient.rememberRoom(room)
        joined.serverAddress()?.let { ServerSettings.save(this, it, "") }
        setResult(RESULT_OK)
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
                JoinedRoomStore.delete(this, room.roomId)
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
                    JoinedRoomStore.save(
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

    private fun JoinedRoom.serverAddress(): String? =
        port.takeIf { it > 0 }?.let(HostedRoomReconnectPolicy::serverAddress)

    companion object {
        private const val EXTRA_SHARE_ROOM_ID = "share_room_id"

        fun shareIntent(context: Context, roomId: String) =
            Intent(context, HostedRoomsActivity::class.java).putExtra(EXTRA_SHARE_ROOM_ID, roomId)
    }
}
