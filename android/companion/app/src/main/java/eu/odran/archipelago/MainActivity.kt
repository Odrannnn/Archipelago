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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
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
    private var pendingRequiredApWorldInvite: RoomInvite? = null
    private var pendingPatchedRom: Pair<String, ByteArray>? = null
    private val refreshStatus = object : Runnable {
        override fun run() {
            status.text = BridgeService.statusText
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
            text = "Starting background bridge…"
            CompanionUi.styleBody(this)
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
            text = "💤 Archipelago waiting for ROM"
            CompanionUi.styleBody(this)
            setPadding(0, CompanionUi.dp(this@MainActivity, 6), 0, 0)
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
            textSize = 15f
        }
        password = EditText(this).apply {
            hint = "Room password (optional)"
            setSingleLine(true)
            setText(savedSettings.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 15f
        }
        val save = Button(this).apply {
            text = "Save and connect"
            CompanionUi.stylePrimary(this)
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
            text = "Generate a seed"
            CompanionUi.styleSecondary(this)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, GeneratorActivity::class.java))
            }
        }
        val openInvite = Button(this).apply {
            text = "Open multiplayer invite"
            CompanionUi.stylePrimary(this)
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
            text = "Saved rooms"
            CompanionUi.styleSecondary(this)
            setOnClickListener {
                startActivityForResult(
                    Intent(this@MainActivity, RoomLibraryActivity::class.java),
                    REQUEST_MANAGE_ROOMS,
                )
            }
        }
        inviteStatus = TextView(this).apply {
            CompanionUi.styleMuted(this)
            setPadding(0, CompanionUi.dp(this@MainActivity, 8), 0, 0)
        }
        joinedRoomContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = CompanionUi.screen(this).apply {
            addView(
                CompanionUi.pageTitle(
                    this@MainActivity,
                    "Archipelago Companion",
                    "Play, generate, and manage mGBA multiworlds from your phone.",
                ),
                CompanionUi.fullWidth(),
            )

            addView(CompanionUi.card(this@MainActivity, "Connection status").apply {
                addView(status, CompanionUi.fullWidth())
                addView(serverStatus, CompanionUi.fullWidth())
                addView(TextView(this@MainActivity).apply {
                    text = "Long-press a status for details. The bridge keeps running in the background."
                    CompanionUi.styleMuted(this)
                    setPadding(0, CompanionUi.dp(this@MainActivity, 8), 0, 0)
                }, CompanionUi.fullWidth())
            }, CompanionUi.cardParams(this@MainActivity))

            addView(CompanionUi.card(this@MainActivity, "Active room").apply {
                addView(joinedRoomContainer, CompanionUi.fullWidth())
                addView(inviteStatus, CompanionUi.fullWidth())
            }, CompanionUi.cardParams(this@MainActivity))

            addView(CompanionUi.card(
                this@MainActivity,
                "Start something",
                "Open an invite from another player or create a new seed.",
            ).apply {
                addView(openInvite, CompanionUi.fullWidth())
                val secondaryActions = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(generator, CompanionUi.weightedButtonParams(this@MainActivity, 6))
                    addView(manageRooms, CompanionUi.weightedButtonParams(this@MainActivity))
                }
                addView(secondaryActions, CompanionUi.insetTop(secondaryActions, this@MainActivity, 6))
            }, CompanionUi.cardParams(this@MainActivity))

            val connectionFields = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(address, CompanionUi.fullWidth())
                addView(password, CompanionUi.fullWidth())
                addView(save, CompanionUi.insetTop(save, this@MainActivity, 6))
                addView(TextView(this@MainActivity).apply {
                    text = "Most invites configure this automatically. Use manual settings for direct server connections."
                    CompanionUi.styleMuted(this)
                    setPadding(0, CompanionUi.dp(this@MainActivity, 8), 0, 0)
                }, CompanionUi.fullWidth())
            }
            addView(CompanionUi.card(this@MainActivity, "Manual connection").apply {
                addView(
                    CompanionUi.toggleButton(this@MainActivity, "connection settings", connectionFields),
                    CompanionUi.fullWidth(),
                )
                addView(connectionFields, CompanionUi.fullWidth())
            }, CompanionUi.cardParams(this@MainActivity))
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
            if (requestCode == REQUEST_INVITE_APWORLD) {
                pendingRequiredApWorldInvite = null
                inviteStatus.text = "APWorld import canceled · invitation not loaded."
            }
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
            REQUEST_INVITE_APWORLD -> installRequiredInviteApWorld(data.data!!)
            REQUEST_SELECT_PATCHED_ROM -> rememberExistingPatchedRom(data.data!!, data.flags)
            REQUEST_SAVE_INVITE_ROM -> {
                val export = pendingPatchedRom ?: return
                val destination = data.data!!
                runCatching {
                    contentResolver.openOutputStream(destination)?.use { it.write(export.second) }
                        ?: error("Could not open the selected destination.")
                }.onSuccess {
                    rememberPatchedRom(export.first, destination, data.flags, export.second.sha256Hex())
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
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Patched game ROM"
        if (!isSupportedRomName(name)) {
            inviteStatus.text = "Select a patched .gba or .gbc file."
            return
        }
        val room = JoinedRoomStore.load(this)
        if (room == null) {
            inviteStatus.text = "There is no active imported room to associate with this ROM."
            return
        }
        inviteStatus.text = "Verifying that $name matches this room's saved ROM…"
        thread {
            val result = runCatching {
                val expectedHash = room.patchedRomSha256
                    ?: room.patchedRomUri?.let { savedUri ->
                        runCatching { sha256(Uri.parse(savedUri)) }.getOrNull()
                    }
                    ?: error(
                        "This room has no verifiable saved ROM. Reopen its player invite, " +
                            "patch the ROM, and save it again.",
                    )
                val selectedHash = sha256(uri)
                require(selectedHash.equals(expectedHash, ignoreCase = true)) {
                    "That is not the ROM previously saved for this room. " +
                        "Select an identical copy or patch and save this room's ROM again."
                }
                selectedHash
            }
            handler.post {
                result.onSuccess { selectedHash ->
                    rememberPatchedRom(name, uri, flags, selectedHash)
                    inviteStatus.text = "Remembered verified ROM $name · ready to launch in RetroArch."
                }.onFailure {
                    inviteStatus.text = "Could not change the saved ROM shortcut: ${it.message}"
                }
            }
        }
    }

    private fun rememberPatchedRom(name: String, uri: Uri, flags: Int, sha256: String) {
        val permissionFlags = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        if (permissionFlags != 0 && flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            runCatching { contentResolver.takePersistableUriPermission(uri, permissionFlags) }
        }
        JoinedRoomStore.rememberPatchedRom(this, name, uri, sha256)?.let { renderJoinedRoom(it) }
    }

    private fun sha256(uri: Uri): String = contentResolver.openInputStream(uri)?.buffered()?.use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            totalBytes += count
            require(totalBytes <= MAX_ROM_BYTES) { "The selected file is too large to be a supported ROM." }
            digest.update(buffer, 0, count)
        }
        require(totalBytes > 0) { "The selected ROM is empty." }
        digest.digest().toHexString()
    } ?: error("Could not read the selected ROM.")

    private fun isSupportedRomName(name: String): Boolean =
        name.endsWith(".gba", ignoreCase = true) || name.endsWith(".gbc", ignoreCase = true)

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .toHexString()

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

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
                loadInviteAfterApWorldCheck(invite)
            }
            .show()
    }

    private fun loadInviteAfterApWorldCheck(invite: RoomInvite) {
        if (!invite.hasPlayerPatch) {
            resolveAndLoadRoom(invite)
            return
        }
        val game = invite.gameName
        if (game.isNullOrBlank()) {
            inviteStatus.text = "Could not load invitation: its player patch does not declare a game."
            return
        }
        if (OfflineGenerator.isBundledGame(game) || ImportedApWorldStore.list(this).any { it.game == game }) {
            resolveAndLoadRoom(invite)
            return
        }
        pendingRequiredApWorldInvite = invite
        AlertDialog.Builder(this)
            .setTitle("$game APWorld required")
            .setMessage(
                "This invitation needs the $game APWorld for ROM patching and live synchronization. " +
                    "APWorlds contain executable Python code, so import one only from an author you trust.",
            )
            .setNegativeButton("Cancel") { _, _ ->
                pendingRequiredApWorldInvite = null
                inviteStatus.text = "Invitation not loaded · $game APWorld is required."
            }
            .setPositiveButton("Import APWorld") { _, _ -> chooseRequiredInviteApWorld() }
            .show()
    }

    private fun chooseRequiredInviteApWorld() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
            },
            REQUEST_INVITE_APWORLD,
        )
    }

    private fun installRequiredInviteApWorld(uri: Uri) {
        val invite = pendingRequiredApWorldInvite ?: return
        val requiredGame = invite.gameName ?: return
        inviteStatus.text = "Validating and installing the required $requiredGame APWorld…"
        thread(name = "invite-apworld-install") {
            var matchingWorldInstalled = false
            runCatching {
                val installed = ImportedApWorldStore.install(this, uri)
                if (installed.game != requiredGame) {
                    ImportedApWorldStore.remove(this, installed.packageName)
                    error("The selected APWorld is for ${installed.game}; this invitation requires $requiredGame.")
                }
                matchingWorldInstalled = true
                val catalog = OfflineGenerator.refreshCatalog(this)
                val capability = catalog.firstOrNull { it.game == requiredGame }
                    ?: error(
                        "The $requiredGame APWorld was installed but did not load: " +
                            shortWorldFailure(OfflineGenerator.cachedWorldFailures()[installed.packageName]),
                    )
                require(capability.romPatch) {
                    "The installed $requiredGame APWorld does not provide compatible ROM patching."
                }
                require(capability.liveBridge) {
                    "The installed $requiredGame APWorld does not provide compatible Android live synchronization."
                }
                installed
            }.onSuccess { installed ->
                pendingRequiredApWorldInvite = null
                runOnUiThread {
                    inviteStatus.text =
                        "Installed ${installed.game} ${installed.worldVersion} · continuing invitation…"
                    resolveAndLoadRoom(invite)
                }
            }.onFailure { error ->
                runOnUiThread {
                    inviteStatus.text = "Could not install the required APWorld: ${error.message}"
                    if (matchingWorldInstalled) {
                        pendingRequiredApWorldInvite = null
                        AlertDialog.Builder(this)
                            .setTitle("Required APWorld unavailable")
                            .setMessage(
                                "${error.message}\n\nOpen Game worlds to inspect or remove it. " +
                                    "A failed world may require restarting the companion before installing another version.",
                            )
                            .setPositiveButton("Close", null)
                            .show()
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Could not import required APWorld")
                            .setMessage(error.message ?: error.javaClass.simpleName)
                            .setNegativeButton("Cancel") { _, _ -> pendingRequiredApWorldInvite = null }
                            .setPositiveButton("Choose another") { _, _ -> chooseRequiredInviteApWorld() }
                            .show()
                    }
                }
            }
        }
    }

    private fun shortWorldFailure(failure: String?): String = failure
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.lastOrNull()
        ?.take(240)
        ?: "no world class was registered"

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
        inviteStatus.text = "Validating the selected base ROM…"
        thread(name = "shared-invite-rom-patching") {
            runCatching {
                val selectedBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read the selected base ROM.")
                val baseBytes = if (BaseRomCache.hasBuiltInValidation(game)) {
                    BaseRomCache.store(this, selectedBytes, game)
                } else {
                    selectedBytes
                }
                baseBytes to selectedBytes.takeUnless { BaseRomCache.hasBuiltInValidation(game) }
            }.onSuccess { (baseBytes, cacheAfterSuccessfulPatch) ->
                patchInviteBaseRom(
                    baseBytes,
                    cachedNow = true,
                    cacheAfterSuccessfulPatch = cacheAfterSuccessfulPatch,
                )
            }.onFailure { error ->
                pendingPlayerInvite = null
                runOnUiThread {
                    inviteStatus.text = "Could not cache the base ROM: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    private fun patchInviteBaseRom(
        baseBytes: ByteArray,
        cachedNow: Boolean,
        cacheAfterSuccessfulPatch: ByteArray? = null,
    ) {
        val invite = pendingPlayerInvite ?: return
        val patchBytes = invite.patchBytes ?: return
        val game = invite.gameName ?: return
        runOnUiThread {
            val baseDescription = if (cachedNow) "selected" else "cached"
            inviteStatus.text = "Creating ${invite.playerName}'s patched $game ROM using the $baseDescription base ROM…"
        }
        thread(name = "shared-invite-rom-patching") {
            runCatching {
                val extension = OfflineGenerator.patchResultExtension(this, patchBytes)
                val outputName =
                    "${File(invite.patchName ?: "Player${invite.playerSlot}.patch").nameWithoutExtension}$extension"
                val output = File(filesDir, "imported_invites/output/$outputName").apply {
                    parentFile?.mkdirs()
                }
                OfflineGenerator.patchRom(this, patchBytes, baseBytes, output).also {
                    if (cacheAfterSuccessfulPatch != null) {
                        BaseRomCache.storeAfterSuccessfulPatch(this, cacheAfterSuccessfulPatch, game)
                    }
                }
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
                text = "No room selected. Open a shared .apinvite file or choose one from Saved rooms."
                CompanionUi.styleMuted(this)
            })
            return
        }
        joinedRoomContainer.addView(TextView(this).apply {
            text = buildString {
                append(if (room.port > 0) "Connected room · archipelago.gg:${room.port}" else "Saved room · no active port yet")
                if (!room.playerName.isNullOrBlank()) {
                    append("\n${room.playerName} · slot ${room.playerSlot}")
                }
                if (room.gameName?.isNotBlank() == true) append(" · ${room.gameName}")
                if (room.players.isNotEmpty()) append("\nPlayers: ${room.players.joinToString()}")
            }
            CompanionUi.styleBody(this)
            setPadding(0, 0, 0, CompanionUi.dp(this@MainActivity, 8))
        }, matchWrapParams())

        val popTrackerButton = Button(this).apply {
            val playerName = room.playerName
            text = when {
                room.port <= 0 -> "PopTracker unavailable until refresh"
                playerName.isNullOrBlank() -> "PopTracker needs a selected player"
                else -> "Open in PopTracker"
            }
            isEnabled = room.port > 0 && !playerName.isNullOrBlank()
            CompanionUi.styleSecondary(this)
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
        }

        if (!room.patchedRomUri.isNullOrBlank()) {
            val actionHeight = CompanionUi.dp(this, 48)
            val romActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val launchButton = Button(this).apply {
                retroArchButton = this
                text = if (RetroArchLauncher.isRunningRom(
                        room.gameName,
                        room.playerSlot,
                        room.serverAddress(),
                    )
                ) {
                    "Return to RetroArch"
                } else {
                    "Launch saved ROM"
                }
                CompanionUi.stylePrimary(this)
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
            }
            romActions.addView(
                launchButton,
                LinearLayout.LayoutParams(0, actionHeight, 1f),
            )
            if (room.playerSlot != null) {
                romActions.addView(Button(this).apply {
                    text = "⚙"
                    textSize = 20f
                    contentDescription = "Change saved ROM shortcut"
                    tooltipText = "Change saved ROM shortcut"
                    minimumWidth = 0
                    setPadding(0, 0, 0, 0)
                    CompanionUi.styleQuiet(this)
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Change saved ROM shortcut?")
                            .setMessage(
                                "Choose another copy of the exact patched ROM already saved for this room. " +
                                    "The app will verify its SHA-256 fingerprint before changing the shortcut. " +
                                    "It does not modify or delete the current ROM.",
                            )
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Pick matching ROM") { _, _ -> chooseExistingPatchedRom() }
                            .show()
                    }
                }, LinearLayout.LayoutParams(
                    actionHeight,
                    actionHeight,
                ).apply {
                    marginStart = CompanionUi.dp(this@MainActivity, 8)
                })
            }
            joinedRoomContainer.addView(romActions, matchWrapParams())
            joinedRoomContainer.addView(popTrackerButton, CompanionUi.insetTop(popTrackerButton, this, 6))
        } else {
            joinedRoomContainer.addView(popTrackerButton, matchWrapParams())
        }

        val moreRoomActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(this@MainActivity).apply {
                text = "Refresh room and reconnect"
                CompanionUi.styleQuiet(this)
                setOnClickListener { resolveAndLoadRoom(room.roomId) }
            }, matchWrapParams())
        }
        if (room.playerSlot != null && room.patchedRomUri.isNullOrBlank()) {
            if (room.patchedRomSha256 != null) {
                moreRoomActions.addView(Button(this).apply {
                    text = "Choose matching patched ROM"
                    CompanionUi.styleQuiet(this)
                    setOnClickListener { chooseExistingPatchedRom() }
                }, CompanionUi.insetTop(View(this), this, 4))
            } else {
                moreRoomActions.addView(TextView(this).apply {
                    text = "No verified ROM is saved for this room. Reopen its player invite to patch and save it."
                    CompanionUi.styleMuted(this)
                    setPadding(0, CompanionUi.dp(this@MainActivity, 8), 0, 0)
                }, matchWrapParams())
            }
        }
        moreRoomActions.addView(Button(this).apply {
            text = "Open room and player patches"
            CompanionUi.styleQuiet(this)
            setOnClickListener {
                openWebUrl("${ArchipelagoWebHostClient.BASE_URL}/room/${room.roomId}")
            }
        }, CompanionUi.insetTop(View(this), this, 4))
        if (room.trackerId.isNotBlank()) {
            moreRoomActions.addView(Button(this).apply {
                text = "Open tracker"
                CompanionUi.styleQuiet(this)
                setOnClickListener {
                    openWebUrl("${ArchipelagoWebHostClient.BASE_URL}/tracker/${room.trackerId}")
                }
            }, CompanionUi.insetTop(View(this), this, 4))
        }
        joinedRoomContainer.addView(
            CompanionUi.toggleButton(this, "room options", moreRoomActions),
            CompanionUi.insetTop(View(this), this, 6),
        )
        joinedRoomContainer.addView(moreRoomActions, matchWrapParams())
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
        private const val REQUEST_INVITE_APWORLD = 306
        private const val MAX_ROM_BYTES = 32L * 1024 * 1024 + 512
    }
}
