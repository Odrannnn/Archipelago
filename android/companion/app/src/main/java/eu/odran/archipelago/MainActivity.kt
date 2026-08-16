package eu.odran.archipelago

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** Starts the persistent bridge service and displays its current status. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var serverStatus: TextView
    private lateinit var inviteStatus: TextView
    private lateinit var address: EditText
    private lateinit var password: EditText
    private lateinit var joinedRoomContainer: LinearLayout
    private var retroArchButton: Button? = null
    private var renderedRoom: JoinedRoom? = null
    private var pendingPlayerInvite: RoomInvite? = null
    private var pendingPatchedRom: Pair<String, ByteArray>? = null
    private val refreshStatus = object : Runnable {
        override fun run() {
            status.text = BridgeService.statusText +
                "\n\nThe bridge continues running when this screen is closed. Use the notification's Stop action to end it."
            serverStatus.text = BridgeService.serverStatusText
            renderedRoom?.let { room ->
                retroArchButton?.text = if (RetroArchLauncher.isRunningRom(
                        room.gameName,
                        room.playerSlot,
                        room.serverAddress(),
                    )
                ) {
                    "Return to RetroArch"
                } else {
                    "Launch saved ROM in RetroArch"
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedSettings = ServerSettings.load(this)
        status = TextView(this).apply {
            textSize = 18f
            text = "Starting background bridge…"
            setOnLongClickListener {
                val details = BridgeService.statusDetails ?: return@setOnLongClickListener false
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("mGBA connection error")
                    .setMessage(details)
                    .setPositiveButton("Close", null)
                    .show()
                true
            }
        }
        serverStatus = TextView(this).apply {
            textSize = 18f
            text = "💤 Archipelago waiting for ROM"
            setOnLongClickListener {
                val details = BridgeService.serverStatusDetails ?: return@setOnLongClickListener false
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Archipelago connection status")
                    .setMessage(details)
                    .setPositiveButton("Close", null)
                    .show()
                true
            }
        }

        address = EditText(this).apply {
            hint = "Server address, e.g. archipelago.gg:45657"
            setSingleLine(true)
            setText(savedSettings.address)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        password = EditText(this).apply {
            hint = "Room password (optional)"
            setSingleLine(true)
            setText(savedSettings.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = Button(this).apply {
            text = "Save and connect"
            setOnClickListener {
                ServerSettings.save(this@MainActivity, address.text.toString(), password.text.toString())
                startForegroundService(
                    Intent(this@MainActivity, BridgeService::class.java)
                        .setAction(BridgeService.ACTION_RECONNECT),
                )
                status.text = "Settings saved · reconnecting…"
                serverStatus.text = "⏳ Archipelago connecting"
            }
        }
        val generator = Button(this).apply {
            text = "Offline seed generator"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, GeneratorActivity::class.java))
            }
        }
        val openInvite = Button(this).apply {
            text = "Open multiplayer invite"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    },
                    REQUEST_OPEN_INVITE,
                )
            }
        }
        val manageRooms = Button(this).apply {
            text = "Manage imported rooms"
            setOnClickListener {
                startActivityForResult(
                    Intent(this@MainActivity, RoomLibraryActivity::class.java),
                    REQUEST_MANAGE_ROOMS,
                )
            }
        }
        inviteStatus = TextView(this).apply { textSize = 16f }
        joinedRoomContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(this@MainActivity).apply {
                text = "Archipelago Android Companion"
                textSize = 24f
            })
            addView(address, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(password, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(save, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(generator, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(openInvite, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(manageRooms, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@MainActivity).apply {
                text = "Active multiplayer room"
                textSize = 22f
                setPadding(0, 24, 0, 8)
            })
            addView(joinedRoomContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(inviteStatus, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(serverStatus, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val scrollView = ScrollView(this).apply { addView(content) }
        SystemBarInsets.apply(window, scrollView)
        setContentView(scrollView)
        renderJoinedRoom(JoinedRoomStore.load(this))

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, BridgeService::class.java))
        handleInvite(intent)
    }

    @Deprecated("Uses the platform file picker result API available to android.app.Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_INVITE_BASE_ROM) pendingPlayerInvite = null
            return
        }
        if (requestCode == REQUEST_MANAGE_ROOMS) {
            val room = JoinedRoomStore.load(this)
            renderJoinedRoom(room)
            if (room == null) {
                inviteStatus.text = "No imported multiplayer room is active."
            } else {
                inviteStatus.text = "Switching rooms · refreshing its current archipelago.gg server…"
                resolveAndLoadRoom(room.roomId)
            }
            return
        }
        if (data?.data == null) return
        when (requestCode) {
            REQUEST_OPEN_INVITE -> handleInvite(Intent(Intent.ACTION_VIEW, data.data))
            REQUEST_INVITE_BASE_ROM -> patchInviteBaseRom(data.data!!)
            REQUEST_SELECT_PATCHED_ROM -> rememberExistingPatchedRom(data.data!!, data.flags)
            REQUEST_SAVE_INVITE_ROM -> {
                val export = pendingPatchedRom ?: return
                val destination = data.data!!
                runCatching {
                    contentResolver.openOutputStream(destination)?.use { it.write(export.second) }
                        ?: error("Could not open the selected destination.")
                }.onSuccess {
                    rememberPatchedRom(export.first, destination, data.flags)
                    inviteStatus.text = "Saved ${export.first} · ready to load in RetroArch."
                    pendingPatchedRom = null
                    offerRetroArchLaunch(export.first, destination)
                }.onFailure {
                    inviteStatus.text = "Could not save ${export.first}: ${it.message}"
                }
            }
        }
    }

    private fun rememberExistingPatchedRom(uri: Uri, flags: Int) {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Patched GBA game.gba"
        if (!name.endsWith(".gba", ignoreCase = true)) {
            inviteStatus.text = "Select a patched .gba file."
            return
        }
        rememberPatchedRom(name, uri, flags)
        inviteStatus.text = "Remembered $name · ready to launch in RetroArch."
    }

    private fun rememberPatchedRom(name: String, uri: Uri, flags: Int) {
        val permissionFlags = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        if (permissionFlags != 0 && flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, permissionFlags) }
        }
        JoinedRoomStore.rememberPatchedRom(this, name, uri)?.let { renderJoinedRoom(it) }
    }

    private fun chooseExistingPatchedRom() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
            },
            REQUEST_SELECT_PATCHED_ROM,
        )
    }

    private fun offerRetroArchLaunch(name: String, uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("ROM ready")
            .setMessage("Saved $name. Launch it now in RetroArch with the custom mGBA Archipelago core?")
            .setNegativeButton("Done", null)
            .setPositiveButton("Launch RetroArch") { _, _ ->
                val room = JoinedRoomStore.load(this)
                    ?.takeIf { it.patchedRomUri == uri.toString() }
                runCatching {
                    RetroArchLauncher.launch(
                        this,
                        uri,
                        room?.gameName,
                        room?.playerSlot,
                        room?.serverAddress(),
                    )
                }.onSuccess { resumed ->
                    inviteStatus.text = if (resumed) {
                        "Returning to $name in RetroArch…"
                    } else {
                        "Launching $name in RetroArch…"
                    }
                }
                    .onFailure {
                        inviteStatus.text = "Could not launch RetroArch: ${it.message ?: it.javaClass.simpleName}"
                    }
            }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInvite(intent)
    }

    private fun handleInvite(sourceIntent: Intent?) {
        val invite = runCatching { RoomInvite.fromIntent(this, sourceIntent) }
            .onFailure { inviteStatus.text = "Could not open invitation: ${it.message}" }
            .getOrNull() ?: return
        // Prevent an activity recreation from presenting the same invitation twice.
        setIntent(Intent(this, MainActivity::class.java))
        AlertDialog.Builder(this)
            .setTitle(if (invite.hasPlayerPatch) "Load ${invite.playerName}'s multiplayer invite?" else "Load shared multiplayer room?")
            .setMessage(
                buildString {
                    if (invite.hasPlayerPatch) {
                        append("This invite is for player slot ${invite.playerSlot} and contains ${invite.patchName}. ")
                    }
                    append("The companion will verify room ${invite.roomId.take(10)}… on archipelago.gg, wake its ")
                    append("server if necessary, and load its current connection address. ")
                    if (invite.hasPlayerPatch) {
                        append("It will use your cached clean ${invite.gameName} base ROM or ask for it once. ")
                    }
                    append("No website-session secret is imported.")
                },
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (invite.hasPlayerPatch) "Load and patch" else "Load room") { _, _ ->
                resolveAndLoadRoom(invite)
            }
            .show()
    }

    private fun resolveAndLoadRoom(roomId: String) = resolveAndLoadRoom(RoomInvite(roomId, ""))

    private fun resolveAndLoadRoom(invite: RoomInvite) {
        inviteStatus.text = "Resolving shared room on archipelago.gg…"
        thread(name = "shared-room-import") {
            runCatching { ArchipelagoWebHostClient(this).resolvePublicRoom(invite.roomId) }
                .onSuccess { room ->
                    val joined = JoinedRoomStore.save(this, room, invite)
                    runOnUiThread {
                        renderJoinedRoom(joined)
                        if (room.lastPort > 0) {
                            val serverAddress = "archipelago.gg:${room.lastPort}"
                            ServerSettings.save(this, serverAddress, "")
                            address.setText(serverAddress)
                            password.setText("")
                            startForegroundService(
                                Intent(this, BridgeService::class.java)
                                    .setAction(BridgeService.ACTION_RECONNECT),
                            )
                            inviteStatus.text = "Invitation loaded · connecting to $serverAddress"
                        } else {
                            inviteStatus.text = if (room.lastPort < 0) {
                                "The invitation was saved, but archipelago.gg reports a server error."
                            } else {
                                "The invitation was saved, but the room is still starting. Tap Refresh room."
                            }
                        }
                        if (invite.hasPlayerPatch) {
                            pendingPlayerInvite = invite
                            patchInviteWithCachedBaseRomOrChoose()
                        }
                    }
                }
                .onFailure { error -> runOnUiThread {
                    inviteStatus.text = "Could not load invitation: ${error.message ?: error.javaClass.simpleName}"
                } }
        }
    }

    private fun chooseInviteBaseRom() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
            },
            REQUEST_INVITE_BASE_ROM,
        )
    }

    private fun patchInviteWithCachedBaseRomOrChoose() {
        val invite = pendingPlayerInvite ?: return
        val game = invite.gameName ?: return
        inviteStatus.text = "Room loaded for ${invite.playerName}. Checking for a cached base ROM…"
        thread(name = "shared-invite-rom-cache-check") {
            val cachedRom = BaseRomCache.load(this, game)
            if (cachedRom == null) {
                runOnUiThread {
                    inviteStatus.text = "Room loaded for ${invite.playerName}. Select your clean base ROM once; it will be cached privately."
                    chooseInviteBaseRom()
                }
            } else {
                patchInviteBaseRom(cachedRom, cachedNow = false)
            }
        }
    }

    private fun patchInviteBaseRom(uri: Uri) {
        val invite = pendingPlayerInvite ?: return
        val game = invite.gameName ?: return
        inviteStatus.text = "Validating and caching the selected base ROM…"
        thread(name = "shared-invite-rom-patching") {
            runCatching {
                val selectedBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read the selected base ROM.")
                BaseRomCache.store(this, selectedBytes, game)
            }.onSuccess { baseBytes ->
                patchInviteBaseRom(baseBytes, cachedNow = true)
            }.onFailure { error ->
                pendingPlayerInvite = null
                runOnUiThread {
                    inviteStatus.text = "Could not cache the base ROM: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    private fun patchInviteBaseRom(baseBytes: ByteArray, cachedNow: Boolean) {
        val invite = pendingPlayerInvite ?: return
        val patchBytes = invite.patchBytes ?: return
        val game = invite.gameName ?: return
        runOnUiThread {
            val cacheDescription = if (cachedNow) "newly cached" else "cached"
            inviteStatus.text = "Creating ${invite.playerName}'s patched $game ROM using the $cacheDescription base ROM…"
        }
        thread(name = "shared-invite-rom-patching") {
            runCatching {
                val outputName = "${File(invite.patchName ?: "Player${invite.playerSlot}.patch").nameWithoutExtension}.gba"
                val output = File(filesDir, "imported_invites/output/$outputName").apply {
                    parentFile?.mkdirs()
                }
                OfflineGenerator.patchRom(this, patchBytes, baseBytes, output)
            }.onSuccess { output ->
                pendingPlayerInvite = null
                val export = output.name to output.readBytes()
                pendingPatchedRom = export
                runOnUiThread {
                    inviteStatus.text = "ROM created for ${invite.playerName}. Choose where to save it."
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_TITLE, export.first)
                        },
                        REQUEST_SAVE_INVITE_ROM,
                    )
                }
            }.onFailure { error ->
                pendingPlayerInvite = null
                runOnUiThread {
                    inviteStatus.text = "Could not patch the base ROM: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    private fun renderJoinedRoom(room: JoinedRoom?) {
        joinedRoomContainer.removeAllViews()
        retroArchButton = null
        renderedRoom = room
        if (room == null) {
            joinedRoomContainer.addView(TextView(this).apply {
                text = "Open a shared .apinvite file to add a room, or choose one from Manage imported rooms."
            })
            return
        }
        joinedRoomContainer.addView(TextView(this).apply {
            text = buildString {
                append(if (room.port > 0) "archipelago.gg:${room.port}" else "Room saved · no active port yet")
                if (!room.playerName.isNullOrBlank()) {
                    append("\nSelected player: ${room.playerName} (slot ${room.playerSlot})")
                }
                if (room.players.isNotEmpty()) append("\n${room.players.joinToString()}")
            }
            textSize = 16f
        })
        joinedRoomContainer.addView(Button(this).apply {
            text = "Refresh room and reconnect"
            setOnClickListener { resolveAndLoadRoom(room.roomId) }
        }, matchWrapParams())
        joinedRoomContainer.addView(Button(this).apply {
            val playerName = room.playerName
            text = when {
                room.port <= 0 -> "Open in PopTracker (refresh room first)"
                playerName.isNullOrBlank() -> "Open in PopTracker (player not selected)"
                else -> "Open in PopTracker"
            }
            isEnabled = room.port > 0 && !playerName.isNullOrBlank()
            setOnClickListener {
                val host = "archipelago.gg:${room.port}"
                val selectedPlayer = room.playerName ?: return@setOnClickListener
                val roomPassword = password.text.toString()
                runCatching {
                    PopTrackerLauncher.launch(this@MainActivity, room.gameName, host, selectedPlayer, roomPassword)
                }.onSuccess {
                    inviteStatus.text = "Opening PopTracker for $selectedPlayer at $host…"
                }.onFailure {
                    inviteStatus.text =
                        "Could not open PopTracker. Make sure the PopTracker Android app is installed and up to date."
                }
            }
        }, matchWrapParams())
        if (!room.patchedRomUri.isNullOrBlank()) {
            joinedRoomContainer.addView(Button(this).apply {
                retroArchButton = this
                text = if (RetroArchLauncher.isRunningRom(
                        room.gameName,
                        room.playerSlot,
                        room.serverAddress(),
                    )
                ) {
                    "Return to RetroArch"
                } else {
                    "Launch saved ROM in RetroArch"
                }
                setOnClickListener {
                    val uri = Uri.parse(room.patchedRomUri)
                    runCatching {
                        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                            check(descriptor.statSize != 0L) { "The saved ROM is empty." }
                        } ?: error("The saved ROM is no longer available.")
                        RetroArchLauncher.launch(
                            this@MainActivity,
                            uri,
                            room.gameName,
                            room.playerSlot,
                            room.serverAddress(),
                        )
                    }.onSuccess { resumed ->
                        inviteStatus.text = if (resumed) {
                            "Returning to ${room.patchedRomName ?: "saved ROM"} in RetroArch…"
                        } else {
                            "Launching ${room.patchedRomName ?: "saved ROM"} in RetroArch…"
                        }
                    }.onFailure {
                        inviteStatus.text =
                            "Could not open the saved ROM. Patch and save it again if it was moved or deleted."
                    }
                }
            }, matchWrapParams())
        }
        if (room.playerSlot != null) {
            joinedRoomContainer.addView(Button(this).apply {
                text = if (room.patchedRomUri.isNullOrBlank()) {
                    "Choose existing patched ROM"
                } else {
                    "Change saved ROM shortcut"
                }
                setOnClickListener { chooseExistingPatchedRom() }
            }, matchWrapParams())
        }
        joinedRoomContainer.addView(Button(this).apply {
            text = "Open room and player patches"
            setOnClickListener {
                openWebUrl("${ArchipelagoWebHostClient.BASE_URL}/room/${room.roomId}")
            }
        }, matchWrapParams())
        if (room.trackerId.isNotBlank()) {
            joinedRoomContainer.addView(Button(this).apply {
                text = "Open tracker"
                setOnClickListener {
                    openWebUrl("${ArchipelagoWebHostClient.BASE_URL}/tracker/${room.trackerId}")
                }
            }, matchWrapParams())
        }
    }

    private fun openWebUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun matchWrapParams() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun JoinedRoom.serverAddress(): String? =
        port.takeIf { it > 0 }?.let { "archipelago.gg:$it" }

    override fun onStart() {
        super.onStart()
        handler.post(refreshStatus)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshStatus)
        super.onStop()
    }

    companion object {
        private const val REQUEST_OPEN_INVITE = 301
        private const val REQUEST_INVITE_BASE_ROM = 302
        private const val REQUEST_SAVE_INVITE_ROM = 303
        private const val REQUEST_SELECT_PATCHED_ROM = 304
        private const val REQUEST_MANAGE_ROOMS = 305
    }
}
