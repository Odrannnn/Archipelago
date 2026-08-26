package eu.odran.archipelago

import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Starts the persistent bridge service and displays its current status. */
class MainActivity : CompanionActivity() {
    private data class RomPatchSession(
        val patchName: String,
        val patchBytes: ByteArray,
        val game: String,
        val playerName: String? = null,
        val rememberForActiveRoom: Boolean = false,
    )

    private data class PatchedRomExport(
        val name: String,
        val bytes: ByteArray,
        val game: String,
        val rememberForActiveRoom: Boolean,
        val playerSlot: Int? = null,
        val serverAddress: String? = null,
    )

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: CompanionStatusView
    private lateinit var serverStatus: CompanionStatusView
    private lateinit var inviteStatus: CompanionStatusView
    private lateinit var address: EditText
    private lateinit var password: EditText
    private lateinit var joinedRoomContainer: LinearLayout
    private lateinit var updateBellButton: ImageButton
    private lateinit var updateBellBadge: TextView
    private var updateCheckRunning = false
    private var updateCheckGeneration = 0
    private var retroArchButton: Button? = null
    private var renderedRoom: JoinedRoom? = null
    private var activeRoomRefreshRunning = false
    private var lastActiveRoomRefreshAt = 0L
    private val isUiTestMode: Boolean
        get() = intent?.getBooleanExtra(EXTRA_UI_TEST_MODE, false) == true
    private var pendingRequiredApWorldInvite: RoomInvite? = null
    private var pendingRequiredApWorldPatch: RomPatchSession? = null
    private var pendingPatchedRom: PatchedRomExport? = null
    private var pendingRomPatch: RomPatchSession? = null
    private var pendingRomRequirements: RomRequirements? = null
    private val pendingRomInputs = linkedMapOf<String, ByteArray>()
    private val pendingRomInputUris = linkedMapOf<String, Uri>()
    private var pendingStreamingDestinationName: String? = null
    private val openPlayerPatchDocument = documentLauncher(::openManualPlayerPatch)
    private val openInviteDocument = documentLauncher { uri ->
        handleInvite(Intent(Intent.ACTION_VIEW, uri))
    }
    private val selectPatchedRomDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data?.data != null) {
            rememberExistingPatchedRom(data.data!!, data.flags)
        }
    }
    private val importInviteApWorld = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) installRequiredInviteApWorld(uri)
        else {
            pendingRequiredApWorldInvite = null
            inviteStatus.text = "APWorld import canceled · invitation not loaded."
        }
    }
    private val importPatchApWorld = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) installRequiredPatchApWorld(uri)
        else {
            pendingRequiredApWorldPatch = null
            inviteStatus.text = "APWorld import canceled · player patch not opened."
        }
    }
    private val selectPatchBaseRom = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) acceptBaseRom(uri)
        else clearPendingRomPatch()
    }
    private val createStreamingRom = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data?.data != null) {
            patchRomDocuments(data.data!!, data.flags)
        } else {
            clearPendingRomPatch()
            inviteStatus.text = "Wind Waker ISO patching canceled."
        }
    }
    private val createPatchedRom = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data?.data != null) {
            savePatchedRom(data.data!!, data.flags)
        } else {
            pendingPatchedRom = null
        }
    }
    private val openNativePlayerDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) openNativePlayerFile(uri)
        else inviteStatus.text = "Player-file selection canceled."
    }
    private val hostedRooms = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) refreshSelectedRoom()
    }
    private val refreshStatus = object : Runnable {
        override fun run() {
            val storedRoom = RoomSessionRepository.activeRoom(this@MainActivity)
            if (storedRoom != renderedRoom) {
                renderJoinedRoom(storedRoom)
                storedRoom?.serverAddress()?.takeUnless { address.hasFocus() }?.let(address::setText)
            }
            refreshActiveHostedRoomIfDue()
            val activeRoom = renderedRoom
            if (SohLauncher.isGame(activeRoom?.gameName)) {
                status.text = "Ship of Harkinian connects directly to Archipelago."
                serverStatus.text = if (activeRoom?.port?.let { it > 0 } == true) {
                    "Ready to launch SoH as ${activeRoom?.playerName ?: "the selected player"}."
                } else {
                    "Refresh the room before launching SoH."
                }
            } else {
                status.text = BridgeService.statusText
                serverStatus.text = BridgeService.serverStatusText
            }
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
        status = CompanionStatusView(this).apply {
            text = "Starting background bridge…"
        }
        serverStatus = CompanionStatusView(this).apply {
            text = "💤 Archipelago waiting for ROM"
        }

        val manualConnection = MainManualConnectionCard(
            this,
            savedSettings,
            onConnect = ::saveManualConnection,
            onOpenGameFile = {
                saveManualConnection()
                openPlayerPatchDocument.launch(openDocumentIntent())
            },
        )
        address = manualConnection.addressEditor
        password = manualConnection.passwordEditor
        inviteStatus = CompanionStatusView(this, hideWhenEmpty = true)
        joinedRoomContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = CompanionUi.screen(this).apply {
            addView(mainHeader(), CompanionUi.fullWidth())

            if (FirstRunGuidePreferences.shouldShow(this@MainActivity)) {
                addView(
                    firstRunGuideCard(this@MainActivity, ::openRoomLibrary),
                    CompanionUi.cardParams(this@MainActivity),
                )
            }

            addView(CompanionUi.card(
                this@MainActivity,
                getString(R.string.active_room_title),
            ).apply {
                addView(joinedRoomContainer, CompanionUi.fullWidth())
                addView(CompanionUi.panel(this@MainActivity).apply {
                    addView(TextView(this@MainActivity).apply {
                        text = getString(R.string.connection_title)
                        textSize = 14f
                        setTextColor(CompanionUi.text)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }, CompanionUi.fullWidth())
                    addView(status, CompanionUi.insetTop(status, this@MainActivity, 4))
                    addView(serverStatus, CompanionUi.fullWidth())
                    addView(Button(this@MainActivity).apply {
                        text = getString(R.string.connection_details)
                        CompanionUi.styleQuiet(this)
                        setOnClickListener { showConnectionDetails() }
                    }, CompanionUi.insetTop(this, this@MainActivity, 4))
                }, CompanionUi.insetTop(status, this@MainActivity, 10))
                addView(inviteStatus, CompanionUi.fullWidth())
            }, CompanionUi.cardParams(this@MainActivity))

            addView(mainStartCard(this@MainActivity, MainStartActions(
                openInvite = { openInviteDocument.launch(openDocumentIntent()) },
                generateSeed = {
                    startActivity(Intent(this@MainActivity, GeneratorActivity::class.java))
                },
                openRooms = ::openRoomLibrary,
            )), CompanionUi.cardParams(this@MainActivity))

            addView(manualConnection.view, CompanionUi.cardParams(this@MainActivity))

        }
        val scrollView = CompanionUi.scrollView(this, content)
        SystemBarInsets.apply(window, scrollView)
        setContentView(scrollView)
        renderJoinedRoom(RoomSessionRepository.activeRoom(this))

        if (!isUiTestMode && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (!isUiTestMode) startForegroundService(Intent(this, BridgeService::class.java))
        handleInvite(intent)
    }

    private fun mainHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val iconRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val menuButton = ImageButton(this@MainActivity).apply {
            contentDescription = getString(R.string.open_app_menu)
            setImageResource(R.drawable.ic_menu)
            setColorFilter(CompanionUi.text)
            setPadding(
                CompanionUi.dp(this@MainActivity, 12),
                CompanionUi.dp(this@MainActivity, 12),
                CompanionUi.dp(this@MainActivity, 12),
                CompanionUi.dp(this@MainActivity, 12),
            )
            val attributes = obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless),
            )
            background = attributes.getDrawable(0)
            attributes.recycle()
            setOnClickListener(::showAppMenu)
        }
        iconRow.addView(menuButton, LinearLayout.LayoutParams(
            CompanionUi.dp(this@MainActivity, 44),
            CompanionUi.dp(this@MainActivity, 44),
        ).apply { marginEnd = CompanionUi.dp(this@MainActivity, 2) })
        iconRow.addView(TextView(this@MainActivity).apply {
            text = getString(R.string.app_name)
            setTextColor(CompanionUi.text)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(
                16,
                27,
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
            setPadding(
                CompanionUi.dp(this@MainActivity, 4),
                0,
                CompanionUi.dp(this@MainActivity, 4),
                0,
            )
        }, LinearLayout.LayoutParams(
            0,
            CompanionUi.dp(this@MainActivity, 44),
            1f,
        ))
        iconRow.addView(updateBell(), LinearLayout.LayoutParams(
            CompanionUi.dp(this@MainActivity, 44),
            CompanionUi.dp(this@MainActivity, 44),
        ).apply { marginStart = CompanionUi.dp(this@MainActivity, 2) })
        addView(iconRow, CompanionUi.fullWidth())
        addView(TextView(this@MainActivity).apply {
            text = "Play, generate, host, and launch supported multiworlds from your phone."
            textSize = 15f
            setTextColor(CompanionUi.textMuted)
            setPadding(
                CompanionUi.dp(this@MainActivity, 4),
                CompanionUi.dp(this@MainActivity, 4),
                CompanionUi.dp(this@MainActivity, 4),
                CompanionUi.dp(this@MainActivity, 8),
            )
        }, CompanionUi.fullWidth())
    }

    private fun updateBell(): FrameLayout = FrameLayout(this).apply {
        updateBellButton = ImageButton(this@MainActivity).apply {
            contentDescription = "Checking component updates"
            setImageResource(R.drawable.ic_notifications)
            setColorFilter(CompanionUi.textMuted)
            setPadding(
                CompanionUi.dp(this@MainActivity, 12),
                CompanionUi.dp(this@MainActivity, 12),
                CompanionUi.dp(this@MainActivity, 12),
                CompanionUi.dp(this@MainActivity, 12),
            )
            val attributes = obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless),
            )
            background = attributes.getDrawable(0)
            attributes.recycle()
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DownloadsUpdatesActivity::class.java))
            }
        }
        addView(updateBellButton, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        updateBellBadge = TextView(this@MainActivity).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minWidth = CompanionUi.dp(this@MainActivity, 18)
            minHeight = CompanionUi.dp(this@MainActivity, 18)
            setPadding(
                CompanionUi.dp(this@MainActivity, 4),
                0,
                CompanionUi.dp(this@MainActivity, 4),
                0,
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(CompanionUi.danger)
                cornerRadius = CompanionUi.dp(this@MainActivity, 10).toFloat()
            }
        }
        addView(updateBellBadge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            CompanionUi.dp(this@MainActivity, 18),
            Gravity.TOP or Gravity.END,
        ))
    }

    private fun refreshUpdateBell() {
        if (updateCheckRunning) return
        updateCheckRunning = true
        val generation = ++updateCheckGeneration
        updateBellButton.contentDescription = "Checking component updates"
        thread(name = "component-update-bell") {
            runCatching { ComponentUpdateChecker(this).check() }
                .onSuccess { summary -> runOnUiThread {
                    if (generation != updateCheckGeneration) return@runOnUiThread
                    updateCheckRunning = false
                    renderUpdateBell(summary.updates)
                } }
                .onFailure { error -> runOnUiThread {
                    if (generation != updateCheckGeneration) return@runOnUiThread
                    updateCheckRunning = false
                    updateBellBadge.visibility = View.GONE
                    updateBellButton.setColorFilter(CompanionUi.textMuted)
                    updateBellButton.contentDescription =
                        "Could not check component updates. Open downloads and updates to retry."
                    updateBellButton.setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Update check failed")
                            .setMessage(error.message ?: error.javaClass.simpleName)
                            .setPositiveButton("Close", null)
                            .show()
                        true
                    }
                } }
        }
    }

    private fun renderUpdateBell(updates: List<ComponentAsset>) {
        updateBellButton.setOnLongClickListener(null)
        if (updates.isEmpty()) {
            updateBellBadge.visibility = View.GONE
            updateBellButton.setColorFilter(CompanionUi.textMuted)
            updateBellButton.contentDescription = "Components are up to date"
            return
        }
        updateBellBadge.text = if (updates.size > 9) "9+" else updates.size.toString()
        updateBellBadge.visibility = View.VISIBLE
        updateBellButton.setColorFilter(CompanionUi.primary)
        val names = updates.joinToString { it.component.displayName }
        updateBellButton.contentDescription =
            "${updates.size} component ${if (updates.size == 1) "update" else "updates"} available: $names"
    }

    private fun showAppMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, MENU_DOWNLOADS_UPDATES, 0, "Downloads and updates")
            menu.add(Menu.NONE, MENU_DOLPHIN_SOCKET, 1, "Dolphin socket")
            menu.add(Menu.NONE, MENU_BACKUP_RESTORE, 2, "Backup and restore")
            menu.add(Menu.NONE, MENU_APPEARANCE, 3, "Appearance")
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_APPEARANCE) {
                    showAppearanceDialog()
                    return@setOnMenuItemClickListener true
                }
                when (item.itemId) {
                    MENU_DOWNLOADS_UPDATES -> DownloadsUpdatesActivity::class.java
                    MENU_DOLPHIN_SOCKET -> DolphinSocketActivity::class.java
                    MENU_BACKUP_RESTORE -> BackupRestoreActivity::class.java
                    else -> null
                }?.let { destination ->
                    startActivity(Intent(this@MainActivity, destination))
                    true
                } ?: false
            }
            show()
        }
    }

    private fun showAppearanceDialog() {
        val modes = CompanionThemeMode.entries
        val selectedMode = CompanionThemePreferences.load(this)
        AlertDialog.Builder(this)
            .setTitle("Appearance")
            .setSingleChoiceItems(
                modes.map { it.label }.toTypedArray(),
                modes.indexOf(selectedMode),
            ) { dialog, selectedIndex ->
                CompanionThemePreferences.save(this, modes[selectedIndex])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun documentLauncher(onSelected: (Uri) -> Unit) = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) onSelected(uri)
    }

    private fun openDocumentIntent(type: String = "*/*") = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        this.type = type
    }

    private fun openRoomLibrary() {
        hostedRooms.launch(Intent(this, HostedRoomsActivity::class.java))
    }

    private fun refreshSelectedRoom() {
        val room = RoomSessionRepository.activeRoom(this)
        renderJoinedRoom(room)
        if (room == null) {
            inviteStatus.text = "No imported multiplayer room is active."
        } else {
            inviteStatus.text = "Active room loaded · checking its current archipelago.gg server…"
            lastActiveRoomRefreshAt = 0L
            refreshActiveHostedRoomIfDue()
        }
    }

    private fun savePatchedRom(destination: Uri, resultFlags: Int) {
        val export = pendingPatchedRom ?: return
        runCatching {
            contentResolver.openOutputStream(destination)?.use { it.write(export.bytes) }
                ?: error("Could not open the selected destination.")
        }.onSuccess {
            if (export.rememberForActiveRoom) {
                rememberPatchedRom(export.name, destination, resultFlags, export.bytes.sha256Hex())
            }
            inviteStatus.text = "Saved ${export.name} · ready to load in RetroArch."
            pendingPatchedRom = null
            offerRetroArchLaunch(
                export.name,
                destination,
                export.game,
                export.playerSlot,
                export.serverAddress,
            )
        }.onFailure {
            inviteStatus.text = "Could not save ${export.name}: ${it.message}"
        }
    }

    private fun saveManualConnection() {
        ServerSettings.save(this, address.text.toString(), password.text.toString())
        startForegroundService(
            Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_RECONNECT),
        )
        status.text = "Settings saved · reconnecting…"
        serverStatus.text = "⏳ Archipelago connecting"
    }

    private fun showConnectionDetails() {
        val details = buildString {
            appendLine(status.text)
            appendLine(serverStatus.text)
            BridgeService.statusDetails?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Client")
                appendLine(it)
            }
            BridgeService.serverStatusDetails?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Archipelago server")
                append(it)
            }
            RoomHealthStateStore.summary(this@MainActivity)?.let {
                appendLine()
                appendLine()
                appendLine("Background room health")
                append(it)
            }
        }.trim()
        AlertDialog.Builder(this)
            .setTitle("Connection details")
            .setMessage(details.ifBlank { "No additional connection details are available." })
            .setNeutralButton("Open console") { _, _ ->
                startActivity(Intent(this, ClientConsoleActivity::class.java))
            }
            .setNegativeButton("Share diagnostics") { _, _ -> shareDiagnostics() }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun shareDiagnostics() {
        val report = CompanionDiagnostics.report(this)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Archipelago Companion diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
        }, "Share diagnostics"))
    }

    private fun rememberExistingPatchedRom(uri: Uri, flags: Int) {
        val room = RoomSessionRepository.activeRoom(this)
        if (room == null) {
            inviteStatus.text = "There is no active imported room to associate with this ROM."
            return
        }
        val isGameCube = isGameCubeRoom(room)
        val name = DolphinLauncher.displayName(this, uri) ?: "Patched game ROM"
        if (isGameCube && !name.endsWith(".iso", ignoreCase = true)) {
            inviteStatus.text = "Select an uncompressed GameCube ISO whose filename ends in .iso."
            return
        }
        if (!isGameCube && !isSupportedRomName(name)) {
            inviteStatus.text = "Select a supported patched .gb, .gbc, .gba, .sfc, or .smc file."
            return
        }
        inviteStatus.text = "Verifying that $name matches this room's saved ROM…"
        thread {
            val result = runCatching {
                if (isGameCube) {
                    val selectedIdentity = DolphinLauncher.readGameCubeDiscIdentity(this, uri)
                    val savedIdentity = room.patchedRomUri?.let { savedUri ->
                        runCatching {
                            DolphinLauncher.readGameCubeDiscIdentity(
                                this,
                                Uri.parse(savedUri),
                                requireIsoName = false,
                            )
                        }.getOrNull()
                    }
                    if (savedIdentity != null) {
                        require(selectedIdentity == savedIdentity) {
                            "That ISO is ${selectedIdentity.gameId}, disc ${selectedIdentity.discNumber}, " +
                                "revision ${selectedIdentity.revision}; this room expects " +
                                "${savedIdentity.gameId}, disc ${savedIdentity.discNumber}, " +
                                "revision ${savedIdentity.revision}."
                        }
                    }
                    null
                } else {
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
            }
            handler.post {
                result.onSuccess { selectedHash ->
                    rememberPatchedRom(name, uri, flags, selectedHash)
                    inviteStatus.text = if (isGameCube) {
                        "Remembered verified disc $name · ready to launch in Dolphin."
                    } else {
                        "Remembered verified ROM $name · ready to launch in RetroArch."
                    }
                }.onFailure {
                    inviteStatus.text = "Could not change the saved ROM shortcut: ${it.message}"
                }
            }
        }
    }

    private fun rememberPatchedRom(name: String, uri: Uri, flags: Int, sha256: String?) {
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
        name.endsWith(".gba", ignoreCase = true) ||
            name.endsWith(".gbc", ignoreCase = true) ||
            name.endsWith(".gb", ignoreCase = true) ||
            name.endsWith(".sfc", ignoreCase = true) ||
            name.endsWith(".smc", ignoreCase = true)

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .toHexString()

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun chooseExistingPatchedRom() {
        val isGameCube = RoomSessionRepository.activeRoom(this)?.let(::isGameCubeRoom) == true
        selectPatchedRomDocument.launch(
            openDocumentIntent(if (isGameCube) "*/*" else "application/octet-stream"),
        )
    }

    private fun offerRetroArchLaunch(
        name: String,
        uri: Uri,
        gameName: String? = null,
        playerSlot: Int? = null,
        serverAddress: String? = null,
    ) {
        val coreDescription = RetroArchLauncher.coreDescription(this, uri)
        AlertDialog.Builder(this)
            .setTitle("ROM ready")
            .setMessage("Saved $name. Launch it now in RetroArch with $coreDescription")
            .setNegativeButton("Done", null)
            .setPositiveButton("Launch RetroArch") { _, _ ->
                val room = RoomSessionRepository.activeRoom(this)
                    ?.takeIf { it.patchedRomUri == uri.toString() }
                runCatching {
                    RetroArchLauncher.launch(
                        this,
                        uri,
                        room?.gameName ?: gameName,
                        room?.playerSlot ?: playerSlot,
                        room?.serverAddress() ?: serverAddress,
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
            .setTitle(
                if (invite.hasPlayerIdentity) {
                    "Load ${invite.playerName}'s multiplayer invite?"
                } else {
                    "Load shared multiplayer room?"
                },
            )
            .setMessage(
                buildString {
                    if (invite.hasNativePlayerFile) {
                        append("This invite is for player slot ${invite.playerSlot} and contains ${invite.patchName}. ")
                        append("The game import screen will confirm the player file and save position. ")
                    } else if (invite.hasPlayerPatch) {
                        append("This invite is for player slot ${invite.playerSlot} and contains ${invite.patchName}. ")
                    } else if (invite.hasPlayerIdentity) {
                        append("This invite is for ${invite.playerName}, slot ${invite.playerSlot}, in ${invite.gameName}. ")
                        if (!SohLauncher.isGame(invite.gameName)) {
                            append("That game does not require a player ROM patch. ")
                        }
                    }
                    append("The companion will verify room ${invite.roomId.take(10)}… on archipelago.gg, wake its ")
                    append("server if necessary, and load its current connection address. ")
                    if (invite.hasNativePlayerFile) {
                        append("It will pass the player file and current server address to the installed game. ")
                    } else if (invite.hasPlayerPatch) {
                        append("It will use your cached clean ${invite.gameName} base ROM or ask for it once. ")
                    } else if (invite.hasPlayerIdentity) {
                        append("It will load the player connection and make the game's launch action available. ")
                    }
                    append("No website-session secret is imported.")
                },
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton(
                when {
                    invite.hasNativePlayerFile -> "Load and open game"
                    invite.hasPlayerPatch -> "Load and patch"
                    invite.hasPlayerIdentity -> "Load player"
                    else -> "Load room"
                },
            ) { _, _ ->
                loadInviteAfterApWorldCheck(invite)
            }
            .show()
    }

    private fun loadInviteAfterApWorldCheck(invite: RoomInvite) {
        if (!invite.hasPlayerPatch || invite.hasNativePlayerFile) {
            resolveAndLoadRoom(invite)
            return
        }
        val game = invite.gameName
        if (game.isNullOrBlank()) {
            inviteStatus.text = "Could not load invitation: its player patch does not declare a game."
            return
        }
        if (OfflineGenerator.isBundledGame(this, game) || ImportedApWorldStore.list(this).any { it.game == game }) {
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
        importInviteApWorld.launch(openDocumentIntent("application/octet-stream"))
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
                val dependencies = provisionDependencies(installed)
                val catalog = OfflineGenerator.refreshCatalog(this)
                val capability = catalog.firstOrNull { it.game == requiredGame }
                    ?: error(OfflineGenerator.cachedWorldFailures()[installed.packageName]
                        ?: "The $requiredGame APWorld was installed but did not register its game.")
                require(capability.romPatch) {
                    "The installed $requiredGame APWorld does not provide compatible ROM patching."
                }
                require(capability.liveBridge) {
                    "The installed $requiredGame APWorld does not provide compatible Android live synchronization."
                }
                installed to dependencies
            }.onSuccess { (installed, dependencies) ->
                pendingRequiredApWorldInvite = null
                runOnUiThread {
                    inviteStatus.text = buildString {
                        append("Installed ${installed.game} ${installed.worldVersion}")
                        if (dependencies.installed.isNotEmpty()) {
                            append(" + ")
                            append(dependencies.installed.joinToString { it.packageName })
                        }
                        if (dependencies.pureInstalled.isNotEmpty()) {
                            append(" + ")
                            append(dependencies.pureInstalled.joinToString { it.packageName })
                        }
                        append(" · continuing invitation…")
                    }
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

    private fun resolveAndLoadRoom(roomId: String) = resolveAndLoadRoom(RoomInvite(roomId, ""))

    private fun resolveAndLoadRoom(invite: RoomInvite) {
        inviteStatus.text = "Resolving shared room on archipelago.gg…"
        thread(name = "shared-room-import") {
            val webHostClient = ArchipelagoWebHostClient(this)
            runCatching { webHostClient.resolvePublicRoom(invite.roomId) }
                .onSuccess { room ->
                    webHostClient.rememberRoom(room)
                    val joined = RoomSessionRepository.activate(this, room, invite)
                    runOnUiThread {
                        renderJoinedRoom(joined)
                        if (room.lastPort > 0) {
                            val serverAddress = "archipelago.gg:${room.lastPort}"
                            ServerSettings.save(this, serverAddress, "")
                            address.setText(serverAddress)
                            password.setText("")
                            if (!invite.hasNativePlayerFile) {
                                startForegroundService(
                                    Intent(this, BridgeService::class.java)
                                        .setAction(BridgeService.ACTION_RECONNECT),
                                )
                            }
                            inviteStatus.text = "Invitation loaded · connecting to $serverAddress"
                        } else {
                            inviteStatus.text = if (room.lastPort < 0) {
                                "The invitation was saved, but archipelago.gg reports a server error."
                            } else {
                                "The invitation was saved, but the room is still starting. Tap Refresh room."
                            }
                        }
                        if (invite.hasNativePlayerFile) {
                            val playerFileName = invite.patchName ?: return@runOnUiThread
                            val playerFileBytes = invite.patchBytes ?: return@runOnUiThread
                            runCatching {
                                PlayerFileLauncher.launch(
                                    this,
                                    playerFileName,
                                    playerFileBytes,
                                    PlayerFileLaunchOptions(
                                        serverAddress = room.lastPort.takeIf { it > 0 }
                                            ?.let { "archipelago.gg:$it" },
                                        password = "",
                                        saveSlot = 0,
                                    ),
                                )
                            }.onSuccess {
                                inviteStatus.text = "Invitation loaded · opening $playerFileName in the game…"
                            }.onFailure { error ->
                                inviteStatus.text =
                                    "Invitation loaded, but the game could not be opened: " +
                                        (error.message ?: error.javaClass.simpleName)
                            }
                        } else if (invite.hasPlayerPatch) {
                            startRomPatch(
                                RomPatchSession(
                                    patchName = invite.patchName ?: "Player${invite.playerSlot}.patch",
                                    patchBytes = invite.patchBytes ?: return@runOnUiThread,
                                    game = invite.gameName ?: return@runOnUiThread,
                                    playerName = invite.playerName,
                                    rememberForActiveRoom = true,
                                ),
                            )
                        }
                    }
                }
                .onFailure { error -> runOnUiThread {
                    inviteStatus.text = "Could not load invitation: ${error.message ?: error.javaClass.simpleName}"
                } }
        }
    }

    private fun openManualPlayerPatch(uri: Uri) {
        val patchName = queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "Player.patch"
        inviteStatus.text = "Reading ${File(patchName).name}…"
        thread(name = "manual-player-patch-read") {
            runCatching {
                val patchBytes = contentResolver.openInputStream(uri)?.use {
                    it.readAtMost(MAX_PATCH_BYTES + 1)
                } ?: error("Could not read the selected player patch.")
                require(patchBytes.isNotEmpty()) { "The selected player patch is empty." }
                require(patchBytes.size <= MAX_PATCH_BYTES) { "The selected player patch is too large." }
                RomPatchSession(
                    patchName = File(patchName).name,
                    patchBytes = patchBytes,
                    game = OfflineGenerator.patchGame(this, patchBytes),
                )
            }.onSuccess { session ->
                runOnUiThread { prepareManualRomPatch(session) }
            }.onFailure { error -> runOnUiThread {
                inviteStatus.text =
                    "Could not open the player patch: ${error.message ?: error.javaClass.simpleName}"
            } }
        }
    }

    private fun openLinkedPlayerPatch(patch: File, room: JoinedRoom) {
        inviteStatus.text = "Reading ${patch.name}…"
        thread(name = "linked-player-patch-read") {
            runCatching {
                val patchBytes = patch.inputStream().use { it.readAtMost(MAX_PATCH_BYTES + 1) }
                require(patchBytes.isNotEmpty()) { "The selected player patch is empty." }
                require(patchBytes.size <= MAX_PATCH_BYTES) { "The selected player patch is too large." }
                RomPatchSession(
                    patchName = patch.name,
                    patchBytes = patchBytes,
                    game = OfflineGenerator.patchGame(this, patchBytes),
                    playerName = room.playerName,
                    rememberForActiveRoom = true,
                )
            }.onSuccess { session ->
                runOnUiThread { prepareManualRomPatch(session) }
            }.onFailure { error -> runOnUiThread {
                inviteStatus.text =
                    "Could not open ${patch.name}: ${error.message ?: error.javaClass.simpleName}"
            } }
        }
    }

    private fun prepareManualRomPatch(session: RomPatchSession) {
        if (OfflineGenerator.isBundledGame(this, session.game) ||
            ImportedApWorldStore.list(this).any { it.game == session.game }
        ) {
            startRomPatch(session)
            return
        }
        pendingRequiredApWorldPatch = session
        AlertDialog.Builder(this)
            .setTitle("${session.game} APWorld required")
            .setMessage(
                "This player patch needs the ${session.game} APWorld for ROM patching and live " +
                    "synchronization. APWorlds contain executable Python code, so import one only " +
                    "from an author you trust.",
            )
            .setNegativeButton("Cancel") { _, _ ->
                pendingRequiredApWorldPatch = null
                inviteStatus.text = "Player patch not opened · ${session.game} APWorld is required."
            }
            .setPositiveButton("Import APWorld") { _, _ -> chooseRequiredPatchApWorld() }
            .show()
    }

    private fun offerDolphinLaunch(name: String, uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Disc image ready")
            .setMessage("Saved $name. Launch it now in the Dolphin Archipelago Android fork?")
            .setNegativeButton("Done", null)
            .setPositiveButton("Launch in Dolphin") { _, _ ->
                runCatching { DolphinLauncher.launch(this, uri) }
                    .onSuccess { inviteStatus.text = "Launching $name in Dolphin…" }
                    .onFailure { error ->
                        inviteStatus.text =
                            "Could not launch Dolphin: ${error.message ?: error.javaClass.simpleName}"
                    }
            }
            .show()
    }

    private fun chooseRequiredPatchApWorld() {
        importPatchApWorld.launch(openDocumentIntent("application/octet-stream"))
    }

    private fun installRequiredPatchApWorld(uri: Uri) {
        val session = pendingRequiredApWorldPatch ?: return
        inviteStatus.text = "Validating and installing the required ${session.game} APWorld…"
        thread(name = "player-patch-apworld-install") {
            var matchingWorldInstalled = false
            runCatching {
                val installed = ImportedApWorldStore.install(this, uri)
                if (installed.game != session.game) {
                    ImportedApWorldStore.remove(this, installed.packageName)
                    error("The selected APWorld is for ${installed.game}; this patch requires ${session.game}.")
                }
                matchingWorldInstalled = true
                val dependencies = provisionDependencies(installed)
                val capability = OfflineGenerator.refreshCatalog(this).firstOrNull { it.game == session.game }
                    ?: error(
                        OfflineGenerator.cachedWorldFailures()[installed.packageName]
                            ?: "The ${session.game} APWorld was installed but did not register its game.",
                    )
                require(capability.romPatch) {
                    "The installed ${session.game} APWorld does not provide compatible ROM patching."
                }
                require(capability.liveBridge) {
                    "The installed ${session.game} APWorld does not provide compatible Android live synchronization."
                }
                installed to dependencies
            }.onSuccess { (installed, dependencies) ->
                pendingRequiredApWorldPatch = null
                runOnUiThread {
                    inviteStatus.text = buildString {
                        append("Installed ${installed.game} ${installed.worldVersion}")
                        if (dependencies.installed.isNotEmpty()) {
                            append(" + ")
                            append(dependencies.installed.joinToString { it.packageName })
                        }
                        if (dependencies.pureInstalled.isNotEmpty()) {
                            append(" + ")
                            append(dependencies.pureInstalled.joinToString { it.packageName })
                        }
                        append(" · continuing patch…")
                    }
                    startRomPatch(session)
                }
            }.onFailure { error -> runOnUiThread {
                inviteStatus.text = "Could not install the required APWorld: ${error.message}"
                if (matchingWorldInstalled) {
                    pendingRequiredApWorldPatch = null
                    AlertDialog.Builder(this)
                        .setTitle("Required APWorld unavailable")
                        .setMessage(error.message ?: error.javaClass.simpleName)
                        .setPositiveButton("Close", null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Could not import required APWorld")
                        .setMessage(error.message ?: error.javaClass.simpleName)
                        .setNegativeButton("Cancel") { _, _ -> pendingRequiredApWorldPatch = null }
                        .setPositiveButton("Choose another") { _, _ -> chooseRequiredPatchApWorld() }
                        .show()
                }
            } }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun chooseBaseRom(requirements: RomRequirements, input: RomInputRequirement) {
        AlertDialog.Builder(this)
            .setTitle("Clean ROM required for ${requirements.game}")
            .setMessage(
                "Requested by the installed game world:\n${input.description}\n\n" +
                    "Select a clean original ROM, not an already patched or randomized ROM. " +
                    "The game world will validate it, and the app will cache it privately for later rooms.",
            )
            .setNegativeButton("Cancel") { _, _ -> clearPendingRomPatch() }
            .setPositiveButton("Choose clean ROM") { _, _ -> openBaseRomPicker(input) }
            .show()
    }

    private fun openBaseRomPicker(input: RomInputRequirement) {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // ROM providers use many console-specific MIME types. Streamed
            // inputs use a wildcard, then the upstream APWorld validates them.
            type = if (pendingRomRequirements?.streaming == true) "*/*" else "application/octet-stream"
        }
        val label = input.description.ifBlank { input.fileName.ifBlank { "clean ROM" } }
        selectPatchBaseRom.launch(Intent.createChooser(picker, "Choose $label"))
    }

    private fun showIncorrectBaseRom(
        requirements: RomRequirements,
        input: RomInputRequirement,
        error: Throwable,
    ) {
        AlertDialog.Builder(this)
            .setTitle("Incorrect clean ROM")
            .setMessage(
                "The ${requirements.game} APWorld rejected the selected file.\n\n" +
                    "Required: ${input.description}\n\n" +
                    (error.message ?: "The file did not pass the APWorld's validation."),
            )
            .setNegativeButton("Cancel") { _, _ -> clearPendingRomPatch() }
            .setPositiveButton("Choose another ROM") { _, _ -> openBaseRomPicker(input) }
            .show()
    }

    private fun startRomPatch(session: RomPatchSession) {
        pendingRomPatch = session
        inviteStatus.text = "${patchStatusSubject(session)} Checking for cached ROM inputs…"
        thread(name = "player-patch-rom-cache-check") {
            runCatching { OfflineGenerator.romRequirements(this, session.patchBytes) }
                .onSuccess { requirements ->
                    pendingRomRequirements = requirements
                    pendingRomInputs.clear()
                    pendingRomInputUris.clear()
                    requirements.inputs.forEach { input ->
                        if (requirements.streaming) {
                            BaseRomDocumentStore.load(this, requirements.game, input.key)?.let { uri ->
                                pendingRomInputUris[input.key] = uri
                            }
                        } else {
                            BaseRomCache.load(this, requirements.game, input.key)?.let { bytes ->
                                pendingRomInputs[input.key] = bytes
                            }
                        }
                    }
                    continueRomSelection()
                }
                .onFailure { error -> runOnUiThread {
                    inviteStatus.text =
                        "Could not determine the required ROMs: ${error.message ?: error.javaClass.simpleName}"
                } }
        }
    }

    private fun continueRomSelection() {
        val session = pendingRomPatch ?: return
        val requirements = pendingRomRequirements ?: return
        val missing = requirements.inputs.firstOrNull {
            if (requirements.streaming) it.key !in pendingRomInputUris else it.key !in pendingRomInputs
        }
        if (missing == null) {
            if (requirements.streaming) {
                val extension = requirements.resultExtension.ifBlank { ".iso" }
                pendingStreamingDestinationName = "${File(session.patchName).nameWithoutExtension}$extension"
                runOnUiThread {
                    createStreamingRom.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = DolphinLauncher.discMimeType(checkNotNull(pendingStreamingDestinationName))
                        putExtra(Intent.EXTRA_TITLE, pendingStreamingDestinationName)
                    })
                }
            } else {
                patchRomInputs(pendingRomInputs.toMap())
            }
            return
        }
        runOnUiThread {
            inviteStatus.text = "${patchStatusSubject(session)} Select ${missing.description}."
            chooseBaseRom(requirements, missing)
        }
    }

    private fun acceptBaseRom(uri: Uri) {
        val session = pendingRomPatch ?: return
        val requirements = pendingRomRequirements ?: return
        val input = requirements.inputs.firstOrNull {
            if (requirements.streaming) it.key !in pendingRomInputUris else it.key !in pendingRomInputs
        } ?: return
        inviteStatus.text = "Reading ${input.description}…"
        thread(name = "shared-invite-rom-input") {
            runCatching {
                if (requirements.streaming) {
                    OfflineGenerator.validateRomInputDocument(this, session.patchBytes, input.key, uri)
                    pendingRomInputUris[input.key] = uri
                    null
                } else {
                    val selectedBytes = contentResolver.openInputStream(uri)?.use {
                        it.readAtMost(MAX_ROM_BYTES.toInt() + 1)
                    } ?: error("Could not read the selected base ROM.")
                    require(selectedBytes.size.toLong() <= MAX_ROM_BYTES) {
                        "The selected base ROM is too large."
                    }
                    OfflineGenerator.validateRomInput(this, session.patchBytes, input.key, selectedBytes)
                    selectedBytes
                }
            }.onSuccess { selectedBytes ->
                if (selectedBytes != null) pendingRomInputs[input.key] = selectedBytes
                continueRomSelection()
            }.onFailure { error ->
                runOnUiThread {
                    inviteStatus.text = "The selected file was rejected. Choose the correct clean ROM."
                    showIncorrectBaseRom(requirements, input, error)
                }
            }
        }
    }

    private fun patchRomInputs(romInputs: Map<String, ByteArray>) {
        val session = pendingRomPatch ?: return
        val requirements = pendingRomRequirements ?: return
        runOnUiThread {
            inviteStatus.text = "Validating ROM inputs and creating the patched ${session.game} ROM…"
        }
        thread(name = "player-rom-patching") {
            runCatching {
                val extension = OfflineGenerator.patchResultExtension(this, session.patchBytes)
                val outputName =
                    "${File(session.patchName).nameWithoutExtension}$extension"
                val output = File(filesDir, "patched_roms/output/$outputName").apply {
                    parentFile?.mkdirs()
                }
                OfflineGenerator.patchRom(this, session.patchBytes, romInputs, output).also {
                    romInputs.forEach { (key, bytes) ->
                        BaseRomCache.storeAfterSuccessfulPatch(this, bytes, requirements.game, key)
                    }
                }
            }.onSuccess { output ->
                val room = RoomSessionRepository.activeRoom(this).takeIf { session.rememberForActiveRoom }
                val export = PatchedRomExport(
                    name = output.name,
                    bytes = output.readBytes(),
                    game = session.game,
                    rememberForActiveRoom = session.rememberForActiveRoom,
                    playerSlot = room?.playerSlot,
                    serverAddress = room?.serverAddress() ?: ServerSettings.load(this).address,
                )
                clearPendingRomPatch()
                pendingPatchedRom = export
                runOnUiThread {
                    inviteStatus.text = "Patched ${session.game} ROM created. Choose where to save it."
                    createPatchedRom.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, export.name)
                    })
                }
            }.onFailure { error ->
                BaseRomCache.forget(this, requirements.game)
                clearPendingRomPatch()
                runOnUiThread {
                    inviteStatus.text = "Could not patch the base ROM: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    private fun patchRomDocuments(destination: Uri, resultFlags: Int) {
        val session = pendingRomPatch ?: return
        val requirements = pendingRomRequirements ?: return
        val inputs = pendingRomInputUris.toMap()
        inviteStatus.text = "Creating the patched ${session.game} ROM…"
        thread(name = "player-disc-patching") {
            runCatching {
                OfflineGenerator.patchRomDocuments(this, session.patchBytes, inputs, destination)
                inputs.forEach { (key, uri) -> BaseRomDocumentStore.store(this, requirements.game, key, uri) }
            }.onSuccess {
                val requestedName = pendingStreamingDestinationName ?: "Patched ${session.game}.iso"
                val name = DolphinLauncher.displayName(this, destination) ?: requestedName
                clearPendingRomPatch()
                runOnUiThread {
                    if (session.rememberForActiveRoom) {
                        rememberPatchedRom(name, destination, resultFlags, null)
                    }
                    if (DolphinLauncher.isSupportedDiscName(name)) {
                        inviteStatus.text = "Saved $name · ready to load in Dolphin."
                        offerDolphinLaunch(name, destination)
                    } else {
                        inviteStatus.text = "Saved patched ${session.game} ROM as $name."
                    }
                }
            }.onFailure { error ->
                BaseRomCache.forget(this, requirements.game)
                clearPendingRomPatch()
                runOnUiThread {
                    inviteStatus.text = "Could not patch the base ROM: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    private fun patchStatusSubject(session: RomPatchSession): String =
        session.playerName?.let { "Room loaded for $it." } ?: "${session.game} player patch loaded."

    private fun clearPendingRomPatch() {
        pendingRomPatch = null
        pendingRomRequirements = null
        pendingRomInputs.clear()
        pendingRomInputUris.clear()
        pendingStreamingDestinationName = null
    }

    private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = maxBytes
        while (remaining > 0) {
            val count = read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    private fun renderJoinedRoom(room: JoinedRoom?) {
        joinedRoomContainer.removeAllViews()
        retroArchButton = null
        renderedRoom = room
        if (room == null) {
            joinedRoomContainer.addView(TextView(this).apply {
                text = getString(R.string.no_active_room)
                CompanionUi.styleMuted(this)
            })
            joinedRoomContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.open_invitation)
                    CompanionUi.stylePrimary(this)
                    setOnClickListener { openInviteDocument.launch(openDocumentIntent()) }
                }, CompanionUi.weightedButtonParams(this@MainActivity, 6))
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.browse_rooms)
                    CompanionUi.styleSecondary(this)
                    setOnClickListener { openRoomLibrary() }
                }, CompanionUi.weightedButtonParams(this@MainActivity))
            }, CompanionUi.insetTop(View(this), this, 8))
            return
        }
        val roomStatus = roomStatusPresentation(
            port = room.port,
            refreshing = activeRoomRefreshRunning,
        )
        joinedRoomContainer.addView(TextView(this).apply {
            text = room.playerName?.takeIf { it.isNotBlank() } ?: "Archipelago room"
            textSize = 20f
            setTextColor(CompanionUi.active)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, matchWrapParams())
        joinedRoomContainer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                CompanionUi.statusChip(this@MainActivity, "ACTIVE", CompanionUi.StatusTone.ACTIVE),
                CompanionUi.wrapContentParams(this@MainActivity, 6),
            )
            addView(
                CompanionUi.statusChip(this@MainActivity, roomStatus),
                CompanionUi.wrapContentParams(this@MainActivity),
            )
        }, CompanionUi.insetTop(View(this), this, 8))
        joinedRoomContainer.addView(TextView(this).apply {
            text = buildString {
                room.gameName.takeIf { it.isNotBlank() }?.let(::append)
                if (room.playerSlot != null) {
                    if (isNotEmpty()) append(" · ")
                    append("Player slot ${room.playerSlot}")
                }
                if (room.port > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("archipelago.gg:${room.port}")
                }
                if (room.players.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(room.players.joinToString())
                }
                append("\n${formatStatusAge(room.updatedAt)}")
            }
            CompanionUi.styleMuted(this)
            setPadding(0, CompanionUi.dp(this@MainActivity, 6), 0, CompanionUi.dp(this@MainActivity, 8))
        }, matchWrapParams())
        val shareRoomButton = Button(this).apply {
            text = "Share multiplayer invite"
            CompanionUi.styleSecondary(this)
            setOnClickListener {
                val webHostClient = ArchipelagoWebHostClient(this@MainActivity)
                val hostedRoom = webHostClient.cachedRooms().firstOrNull { it.roomId == room.roomId }
                    ?: room.toHostedRoom()
                webHostClient.rememberRoom(hostedRoom)
                hostedRooms.launch(HostedRoomsActivity.shareIntent(this@MainActivity, room.roomId))
            }
        }

        val isSohRoom = SohLauncher.isGame(room.gameName)
        val linkedPlayerPatch = linkedPlayerPatch(room)
        val linkedPlayerFiles = linkedPlayerFiles(room)
        val playerFileHandler = PlayerFileLauncher.handlerForGame(room.gameName)
            ?: linkedPlayerFiles.firstNotNullOfOrNull { PlayerFileLauncher.handlerFor(it.name) }
        val popTrackerButton = if (isSohRoom) null else Button(this).apply {
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

        if (!isSohRoom && linkedPlayerPatch != null && room.patchedRomUri.isNullOrBlank()) {
            joinedRoomContainer.addView(Button(this).apply {
                text = room.playerName?.takeIf { it.isNotBlank() }?.let { "Patch $it's game" }
                    ?: "Patch player game"
                CompanionUi.stylePrimary(this)
                setOnClickListener { openLinkedPlayerPatch(linkedPlayerPatch, room) }
            }, matchWrapParams())
        }

        if (isSohRoom && !room.playerName.isNullOrBlank()) {
            joinedRoomContainer.addView(Button(this).apply {
                text = if (room.port > 0) {
                    "Launch Ship of Harkinian"
                } else {
                    "SoH unavailable until refresh"
                }
                isEnabled = room.port > 0
                CompanionUi.stylePrimary(this)
                setOnClickListener {
                    val host = room.serverAddress() ?: return@setOnClickListener
                    val selectedPlayer = room.playerName ?: return@setOnClickListener
                    SohLauncher.promptAndLaunch(
                        this@MainActivity,
                        host,
                        selectedPlayer,
                        onLaunched = {
                            inviteStatus.text = "Launching Ship of Harkinian as $selectedPlayer at $host…"
                        },
                        onFailure = {
                            inviteStatus.text =
                                "Could not launch Ship of Harkinian. Make sure the Archipelago-enabled SoH Android app is installed."
                        },
                    )
                }
            }, matchWrapParams())
        }

        if (playerFileHandler != null) {
            joinedRoomContainer.addView(Button(this).apply {
                text = "Import into ${playerFileHandler.appName}"
                CompanionUi.stylePrimary(this)
                setOnClickListener {
                    when (linkedPlayerFiles.size) {
                        0 -> chooseNativePlayerFile(playerFileHandler)
                        1 -> launchNativePlayerFile(linkedPlayerFiles.single())
                        else -> AlertDialog.Builder(this@MainActivity)
                            .setTitle("Choose ${playerFileHandler.appName} player")
                            .setItems(linkedPlayerFiles.map { file ->
                                PlayerFileLauncher.embeddedPlayerName(file)?.let { "$it · ${file.name}" }
                                    ?: file.name
                            }.toTypedArray()) { _, index ->
                                launchNativePlayerFile(linkedPlayerFiles[index])
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }, matchWrapParams())
            if (linkedPlayerFiles.isEmpty()) {
                joinedRoomContainer.addView(TextView(this).apply {
                    text = "The room is not linked to a local ${playerFileHandler.extension} yet; " +
                        "the import button will let you choose one."
                    CompanionUi.styleMuted(this)
                }, CompanionUi.insetTop(View(this), this, 4))
            }
        }

        if (!isSohRoom && !room.patchedRomUri.isNullOrBlank()) {
            val launchInDolphin = isGameCubeRoom(room)
            val actionHeight = CompanionUi.dp(this, 48)
            val romActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val launchButton = Button(this).apply {
                if (!launchInDolphin) retroArchButton = this
                text = if (launchInDolphin) {
                    "Launch in Dolphin"
                } else if (RetroArchLauncher.isRunningRom(
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
                        if (launchInDolphin) {
                            DolphinLauncher.launch(this@MainActivity, uri)
                            false
                        } else {
                            RetroArchLauncher.launch(
                                this@MainActivity,
                                uri,
                                room.gameName,
                                room.playerSlot,
                                room.serverAddress(),
                            )
                        }
                    }.onSuccess { resumed ->
                        inviteStatus.text = if (launchInDolphin) {
                            "Launching ${room.patchedRomName ?: "saved disc image"} in Dolphin…"
                        } else if (resumed) {
                            "Returning to ${room.patchedRomName ?: "saved ROM"} in RetroArch…"
                        } else {
                            "Launching ${room.patchedRomName ?: "saved ROM"} in RetroArch…"
                        }
                    }.onFailure { error ->
                        inviteStatus.text = if (launchInDolphin) {
                            "Could not launch Dolphin: ${error.message ?: error.javaClass.simpleName}"
                        } else {
                            "Could not open the saved ROM. Patch and save it again if it was moved or deleted."
                        }
                    }
                }
            }
            romActions.addView(
                launchButton,
                LinearLayout.LayoutParams(0, actionHeight, 1f),
            )
            if (room.playerSlot != null && (room.patchedRomSha256 != null || launchInDolphin)) {
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
                            .setMessage(if (launchInDolphin) {
                                "Choose the patched uncompressed ISO for this room. The app will compare its " +
                                    "GameCube game ID, disc number, and revision with the currently saved disc " +
                                    "when that document is still readable. " +
                                    "It does not modify or delete either file."
                            } else {
                                "Choose another copy of the exact patched ROM already saved for this room. " +
                                    "The app will verify its SHA-256 fingerprint before changing the shortcut. " +
                                    "It does not modify or delete the current ROM."
                            })
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton(if (launchInDolphin) "Pick matching ISO" else "Pick matching ROM") {
                                _, _ -> chooseExistingPatchedRom()
                            }
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
            val trackerButton = checkNotNull(popTrackerButton)
            joinedRoomContainer.addView(trackerButton, CompanionUi.insetTop(trackerButton, this, 6))
        } else if (popTrackerButton != null) {
            joinedRoomContainer.addView(popTrackerButton, matchWrapParams())
        }

        val commonActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(shareRoomButton, CompanionUi.weightedButtonParams(this@MainActivity, 6))
            // This container is rebuilt after every hosted-room refresh, so each child must be a new View.
            addView(Button(this@MainActivity).apply {
                text = "Console"
                CompanionUi.styleSecondary(this)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, ClientConsoleActivity::class.java))
                }
            }, CompanionUi.weightedButtonParams(this@MainActivity))
        }
        joinedRoomContainer.addView(commonActions, CompanionUi.insetTop(commonActions, this, 8))

        val moreRoomActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Button(this@MainActivity).apply {
                text = "Reconnect"
                CompanionUi.styleQuiet(this)
                setOnClickListener { resolveAndLoadRoom(room.roomId) }
            }, matchWrapParams())
        }
        if (!isSohRoom) {
            moreRoomActions.addView(Switch(this).apply {
                text = "Force server delivery of local items"
                isChecked = room.forceLocalItemsFromServer
                var changingProgrammatically = false
                setOnCheckedChangeListener { _, enabled ->
                    if (changingProgrammatically) return@setOnCheckedChangeListener

                    fun applySetting(value: Boolean) {
                        val updated = RoomSessionRepository.setForceLocalItemsFromServer(
                            this@MainActivity,
                            room.roomId,
                            value,
                        ) ?: return
                        renderJoinedRoom(updated)
                        startForegroundService(
                            Intent(this@MainActivity, BridgeService::class.java)
                                .setAction(BridgeService.ACTION_RECONNECT),
                        )
                        inviteStatus.text = if (value) {
                            "Local-item server delivery enabled · reconnecting the active client."
                        } else {
                            "Strict upstream item handling restored · reconnecting the active client."
                        }
                    }

                    if (!enabled) {
                        applySetting(false)
                        return@setOnCheckedChangeListener
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Force local items through the server?")
                        .setMessage(
                            "This optional compatibility enhancement overrides the game client's upstream " +
                                "local-items handling for this room. Archipelago will include self-owned " +
                                "items in ReceivedItems so the client can restore them after a save rollback.\n\n" +
                                "A ROM which already awards those items locally may grant them twice, " +
                                "especially consumables. Leave this off for strict desktop-client behavior.",
                        )
                        .setNegativeButton("Cancel") { _, _ ->
                            changingProgrammatically = true
                            isChecked = false
                            changingProgrammatically = false
                        }
                        .setPositiveButton("Enable override") { _, _ -> applySetting(true) }
                        .setOnCancelListener {
                            changingProgrammatically = true
                            isChecked = false
                            changingProgrammatically = false
                        }
                        .show()
                }
            }, CompanionUi.insetTop(View(this), this, 4))
            moreRoomActions.addView(TextView(this).apply {
                text = "Off keeps upstream desktop behavior. On requests self-owned items from the server too."
                CompanionUi.styleMuted(this)
                setPadding(0, CompanionUi.dp(this@MainActivity, 2), 0, CompanionUi.dp(this@MainActivity, 6))
            }, matchWrapParams())
            moreRoomActions.addView(Button(this).apply {
                text = "Force item sync from server"
                CompanionUi.styleQuiet(this)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Force item sync from server?")
                        .setMessage(
                            "This is an optional compatibility enhancement, not strict desktop behavior. " +
                                "The companion will clear only its cached received-item history and ask " +
                                "Archipelago for a complete replay. The active game client will compare " +
                                "that history with the ROM's receive counter; ROM inventory and save data " +
                                "are not overwritten. Use this after a rollback or suspected missing item.",
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Force sync") { _, _ ->
                            ClientConsoleStore.submit("/force_item_sync")
                            startForegroundService(Intent(this@MainActivity, BridgeService::class.java))
                            inviteStatus.text =
                                "Forced item sync requested · open the client console for the result."
                        }
                        .show()
                }
            }, CompanionUi.insetTop(View(this), this, 4))
        }
        if (!isSohRoom && playerFileHandler == null && room.playerSlot != null && room.patchedRomUri.isNullOrBlank()) {
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
            text = if (isSohRoom) "Open room" else "Open room and player patches"
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
            CompanionUi.toggleButton(this, "troubleshooting", moreRoomActions),
            CompanionUi.insetTop(View(this), this, 6),
        )
        joinedRoomContainer.addView(moreRoomActions, matchWrapParams())
    }

    private fun isGameCubeRoom(room: JoinedRoom): Boolean =
        DolphinLauncher.isGameCubeGame(this, room.gameName) ||
            room.patchedRomName?.let(DolphinLauncher::isSupportedDiscName) == true ||
            OfflineGenerator.cachedCatalog().any { capability ->
                capability.game == room.gameName &&
                    "dolphin" in capability.emulatorBackends
            }

    private fun JoinedRoom.toHostedRoom() = HostedRoom(
        roomId = roomId,
        seedId = "",
        creationTime = "",
        lastActivity = "",
        lastPort = port,
        timeoutSeconds = 0,
        trackerId = trackerId,
        players = players,
    )

    private fun refreshActiveHostedRoomIfDue() {
        val room = renderedRoom ?: return
        if (!ArchipelagoWebHostClient.ROOM_ID_PATTERN.matches(room.roomId)) return
        val now = System.currentTimeMillis()
        if (activeRoomRefreshRunning ||
            now >= lastActiveRoomRefreshAt && now - lastActiveRoomRefreshAt < ACTIVE_ROOM_REFRESH_INTERVAL_MILLIS
        ) return
        activeRoomRefreshRunning = true
        lastActiveRoomRefreshAt = now
        renderJoinedRoom(room)
        thread(name = "active-hosted-room-refresh") {
            val webHostClient = ArchipelagoWebHostClient(this)
            runCatching { webHostClient.refreshPublicRoom(room.roomId) }
                .onSuccess { resolved ->
                    webHostClient.rememberRoom(resolved)
                    val selected = RoomSessionRepository.activeRoom(this)
                    if (selected?.roomId != room.roomId) {
                        runOnUiThread { activeRoomRefreshRunning = false }
                        return@onSuccess
                    }
                    val refresh = RoomSessionRepository.synchronizeActive(this, resolved)
                        ?: return@onSuccess
                    val updated = refresh.updated
                    val settings = ServerSettings.load(this)
                    val refreshedAddress = refresh.updatedAddress
                    val reconnect = refreshedAddress != null && (
                        refresh.addressChanged ||
                            HostedRoomReconnectPolicy.matchingRoom(settings.address, updated) == null
                        )
                    runOnUiThread {
                        activeRoomRefreshRunning = false
                        if (RoomSessionRepository.activeRoom(this)?.roomId != updated.roomId) return@runOnUiThread
                        renderJoinedRoom(updated)
                        if (!address.hasFocus()) refreshedAddress?.let { address.setText(it) }
                        if (refresh.portChanged) {
                            inviteStatus.text = when {
                                updated.port > 0 -> "Room server updated · archipelago.gg:${updated.port}"
                                updated.port < 0 -> "The active hosted room reports a server error."
                                else -> "The active hosted room is still starting."
                            }
                        }
                        if (reconnect) {
                            startForegroundService(
                                Intent(this, BridgeService::class.java)
                                    .setAction(BridgeService.ACTION_RECONNECT),
                            )
                        }
                    }
                }
                .onFailure { error -> runOnUiThread {
                    activeRoomRefreshRunning = false
                    renderJoinedRoom(RoomSessionRepository.activeRoom(this))
                    inviteStatus.show(
                        "Could not refresh the active room: ${error.message ?: error.javaClass.simpleName}",
                        CompanionStatusLevel.ERROR,
                    )
                } }
        }
    }

    private fun linkedPlayerFiles(room: JoinedRoom): List<File> {
        val historyId = HostedRoomHistoryLinks.historyId(this, room.roomId) ?: return emptyList()
        val entry = SeedHistoryStore.list(this).firstOrNull { it.id == historyId } ?: return emptyList()
        val candidates = entry.files
            .map { File(it.path) }
            .filter { file ->
                file.isFile && PlayerFileLauncher.handlerFor(file.name)?.let { handler ->
                    room.gameName.isBlank() || handler.gameName.equals(room.gameName, ignoreCase = true)
                } == true
            }
        val playerName = room.playerName?.takeIf { it.isNotBlank() } ?: return candidates
        val exact = candidates.filter {
            PlayerFileLauncher.embeddedPlayerName(it).equals(playerName, ignoreCase = true)
        }
        return when {
            exact.isNotEmpty() -> exact
            candidates.size == 1 -> candidates
            else -> emptyList()
        }
    }

    private fun linkedPlayerPatch(room: JoinedRoom): File? {
        val playerSlot = room.playerSlot ?: return null
        val historyId = HostedRoomHistoryLinks.historyId(this, room.roomId) ?: return null
        val entry = SeedHistoryStore.list(this).firstOrNull { it.id == historyId } ?: return null
        return entry.patches.mapIndexedNotNull { index, artifact ->
            File(artifact.path).takeIf { it.isFile }?.let { file ->
                val slot = Regex("(?:^|_)P(\\d+)(?:_|\\.)", RegexOption.IGNORE_CASE)
                    .find(artifact.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)
                slot to file
            }
        }.firstOrNull { (slot, _) -> slot == playerSlot }?.second
    }

    private fun launchNativePlayerFile(playerFile: File) {
        val room = RoomSessionRepository.activeRoom(this)
        val options = PlayerFileLaunchOptions(
            serverAddress = room?.serverAddress(),
            password = password.text.toString(),
            saveSlot = 0,
        )
        runCatching { PlayerFileLauncher.launch(this, playerFile, options) }
            .onSuccess {
                val appName = PlayerFileLauncher.handlerFor(playerFile.name)?.appName ?: "the game"
                val server = room?.serverAddress() ?: "the selected server"
                inviteStatus.text = "Sent ${playerFile.name}, $server, and save position 1 to $appName."
            }
            .onFailure { error ->
                inviteStatus.text = "Could not open ${playerFile.name}: ${error.message ?: error.javaClass.simpleName}"
            }
    }

    private fun chooseNativePlayerFile(handler: PlayerFileHandler) {
        inviteStatus.text = "Choose the ${handler.extension} for this room."
        openNativePlayerDocument.launch(openDocumentIntent())
    }

    private fun openNativePlayerFile(uri: Uri) {
        val room = RoomSessionRepository.activeRoom(this)
        val expectedHandler = PlayerFileLauncher.handlerForGame(room?.gameName)
        val name = queryDisplayName(uri)?.let { File(it).name }.orEmpty()
        val selectedHandler = PlayerFileLauncher.handlerFor(name)
        if (expectedHandler == null || selectedHandler != expectedHandler) {
            inviteStatus.text = "Select the ${expectedHandler?.extension ?: "native player file"} required by this room."
            return
        }
        val options = PlayerFileLaunchOptions(
            serverAddress = room?.serverAddress(),
            password = password.text.toString(),
            saveSlot = 0,
        )
        runCatching { PlayerFileLauncher.launch(this, uri, name, options) }
            .onSuccess {
                val server = room?.serverAddress() ?: "the selected server"
                inviteStatus.text = "Sent $name, $server, and save position 1 to ${selectedHandler.appName}."
            }
            .onFailure { error ->
                inviteStatus.text = "Could not open $name: ${error.message ?: error.javaClass.simpleName}"
            }
    }

    private fun openWebUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun provisionDependencies(world: ImportedApWorld): NativeDependencyProvisionResult {
        val provisioned = NativeDependencyProvisioner.installFor(
            this,
            listOf(world),
            onStarting = { asset -> runOnUiThread {
                inviteStatus.text =
                    "Installing reviewed Android dependency ${asset.packageName} ${asset.version}…"
            } },
            onProgress = { asset, downloaded, total -> runOnUiThread {
                inviteStatus.text =
                    "Downloading ${asset.packageName}: ${formatDependencyBytes(downloaded)} / ${formatDependencyBytes(total)}"
            } },
        )
        check(provisioned.unresolved.isEmpty()) {
            provisioned.unresolved.joinToString(
                prefix = "Some APWorld dependencies are not available for Android yet: ",
                separator = "; ",
            ) { "${it.requirement} (${it.reason})" }
        }
        return provisioned
    }

    private fun formatDependencyBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun matchWrapParams() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun JoinedRoom.serverAddress(): String? =
        port.takeIf { it > 0 }?.let { "archipelago.gg:$it" }

    override fun onStart() {
        super.onStart()
        if (!isUiTestMode) ActiveRoomHealthScheduler.appEnteredForeground(this)
        lastActiveRoomRefreshAt = 0L
        if (!isUiTestMode) {
            handler.post(refreshStatus)
            refreshUpdateBell()
        }
    }

    override fun onStop() {
        handler.removeCallbacks(refreshStatus)
        if (!isUiTestMode) ActiveRoomHealthScheduler.appEnteredBackground(this)
        super.onStop()
    }

    companion object {
        private const val MENU_DOWNLOADS_UPDATES = 400
        private const val MENU_DOLPHIN_SOCKET = 401
        private const val MENU_BACKUP_RESTORE = 402
        private const val MENU_APPEARANCE = 403
        private const val MAX_PATCH_BYTES = 32 * 1024 * 1024
        private const val MAX_ROM_BYTES = 32L * 1024 * 1024 + 512
        private const val ACTIVE_ROOM_REFRESH_INTERVAL_MILLIS = 60_000L
        internal const val EXTRA_UI_TEST_MODE = "ui_test_mode"
    }
}
