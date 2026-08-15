package gg.archipelago.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Creates player YAMLs, generates seeds, and patches a user-supplied ROM entirely offline. */
class GeneratorActivity : Activity() {
    private lateinit var yamlEditor: EditText
    private lateinit var seedEditor: EditText
    private lateinit var playerCountView: TextView
    private lateinit var generateButton: Button
    private lateinit var exportSeedButton: Button
    private lateinit var hostSeedButton: Button
    private lateinit var patchButton: Button
    private lateinit var patchesContainer: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var hostedRoomsContainer: LinearLayout
    private lateinit var refreshHostedRoomsButton: Button
    private lateinit var status: TextView
    private lateinit var webHostClient: ArchipelagoWebHostClient

    private var seedFile: File? = null
    private var patchFile: File? = null
    private var availablePatches: List<File> = emptyList()
    private var pendingExport: Pair<String, ByteArray>? = null
    private var historySettingsLoaded = false
    private var currentHistoryId: String? = null
    private var hostingInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webHostClient = ArchipelagoWebHostClient(this)

        yamlEditor = EditText(this).apply {
            minLines = 28
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            isEnabled = false
            setText("Loading embedded generator…")
        }
        seedEditor = EditText(this).apply {
            hint = "Numeric seed (optional)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        playerCountView = TextView(this).apply {
            text = "Players: 1"
            textSize = 16f
        }
        status = TextView(this).apply {
            text = "Starting Python 3.12…"
            textSize = 16f
        }
        patchesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        hostedRoomsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val addPlayerButton = Button(this).apply {
            text = "Add player"
            setOnClickListener { addPlayer() }
        }
        val removePlayerButton = Button(this).apply {
            text = "Remove last"
            setOnClickListener { removeLastPlayer() }
        }
        val playerButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(addPlayerButton, weightedButtonParams())
            addView(removePlayerButton, weightedButtonParams())
        }
        val saveYamlButton = Button(this).apply {
            text = "Save player YAML"
            setOnClickListener {
                val name = if (yamlDocuments().size > 1) "Players.yaml" else "Player.yaml"
                beginExport(name, yamlEditor.text.toString().toByteArray())
            }
        }
        generateButton = Button(this).apply {
            text = "Generate offline"
            isEnabled = false
            setOnClickListener { generateSeed() }
        }
        exportSeedButton = Button(this).apply {
            text = "Save seed ZIP"
            isEnabled = false
            setOnClickListener {
                seedFile?.takeIf { it.isFile }?.let { beginExport(it.name, it.readBytes()) }
            }
        }
        hostSeedButton = Button(this).apply {
            text = "Host seed on archipelago.gg"
            isEnabled = false
            setOnClickListener { seedFile?.let { hostSeed(it, currentHistoryId) } }
        }
        patchButton = Button(this).apply {
            text = "Create patched GBA"
            isEnabled = false
            setOnClickListener { chooseBaseRom() }
        }

        yamlEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) =
                updatePlayerCount()

            override fun afterTextChanged(text: Editable?) = Unit
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@GeneratorActivity).apply {
                text = "Offline Metroid Fusion Generator"
                textSize = 24f
            })
            addView(TextView(this@GeneratorActivity).apply {
                text = "Add players with the controls below, then edit each YAML document if they need different " +
                    "settings. Generation and patching need no network connection. Your base ROM is only read " +
                    "after you select it."
            })
            addView(seedEditor, matchWrapParams())
            addView(playerCountView, matchWrapParams())
            addView(playerButtons, matchWrapParams())
            addView(yamlEditor, matchWrapParams())
            addView(saveYamlButton, matchWrapParams())
            addView(generateButton, matchWrapParams())
            addView(exportSeedButton, matchWrapParams())
            addView(hostSeedButton, matchWrapParams())
            addView(patchesContainer, matchWrapParams())
            addView(patchButton, matchWrapParams())
            addView(status, matchWrapParams())
            addView(TextView(this@GeneratorActivity).apply {
                text = "Hosted instances"
                textSize = 22f
                setPadding(0, 32, 0, 8)
            })
            addView(TextView(this@GeneratorActivity).apply {
                text = "Rooms uploaded by this app are tied to its private archipelago.gg website session. " +
                    "Refresh to retrieve the current website list."
            })
            refreshHostedRoomsButton = Button(this@GeneratorActivity).apply {
                text = "Refresh hosted instances"
                setOnClickListener { refreshHostedRooms() }
            }
            addView(refreshHostedRoomsButton, matchWrapParams())
            addView(Button(this@GeneratorActivity).apply {
                text = "Sync website session"
                setOnClickListener { confirmWebsiteSessionSync() }
            }, matchWrapParams())
            addView(Button(this@GeneratorActivity).apply {
                text = "Open website instance list"
                setOnClickListener { openWebUrl("${ArchipelagoWebHostClient.BASE_URL}/user-content") }
            }, matchWrapParams())
            addView(hostedRoomsContainer, matchWrapParams())
            addView(TextView(this@GeneratorActivity).apply {
                text = "Generated seed history"
                textSize = 22f
                setPadding(0, 32, 0, 8)
            })
            addView(historyContainer, matchWrapParams())
        }
        setContentView(ScrollView(this).apply { addView(content) })
        renderHostedRooms(webHostClient.cachedRooms())
        renderHistory()

        thread(name = "offline-generator-startup") {
            runCatching { OfflineGenerator.defaultYaml(this) }
                .onSuccess { template ->
                    runOnUiThread {
                        if (!historySettingsLoaded) yamlEditor.setText(template)
                        yamlEditor.isEnabled = true
                        generateButton.isEnabled = true
                        status.text = "Ready · Metroid Fusion APWorld 1.22.4"
                    }
                }
                .onFailure { showError("Could not start the offline generator", it) }
        }
    }

    private fun addPlayer() {
        if (!yamlEditor.isEnabled) return
        val documents = yamlDocuments().ifEmpty { listOf(OfflineGenerator.defaultYaml(this)) }
        val newDocument = replacePlayerName(documents.last(), "Player ${documents.size + 1}")
        yamlEditor.setText((documents + newDocument).joinToString("\n---\n"))
        yamlEditor.setSelection(yamlEditor.text.length)
        status.text = "Added Player ${documents.size + 1}. Edit the new YAML document if needed."
    }

    private fun removeLastPlayer() {
        val documents = yamlDocuments()
        if (documents.size <= 1) {
            status.text = "A seed needs at least one player."
            return
        }
        yamlEditor.setText(documents.dropLast(1).joinToString("\n---\n"))
        yamlEditor.setSelection(yamlEditor.text.length)
        status.text = "Removed the last player."
    }

    private fun yamlDocuments(text: String = yamlEditor.text.toString()): List<String> {
        val documents = mutableListOf<StringBuilder>()
        text.lineSequence().forEach { line ->
            if (line.trim() == "---") {
                documents += StringBuilder()
            } else {
                if (documents.isEmpty()) documents += StringBuilder()
                documents.last().appendLine(line)
            }
        }
        return documents.map { it.toString().trim() }.filter { it.isNotBlank() }
    }

    private fun replacePlayerName(yaml: String, playerName: String): String {
        val nameLine = Regex("(?m)^name\\s*:.*$")
        return if (nameLine.containsMatchIn(yaml)) {
            nameLine.replaceFirst(yaml, "name: $playerName")
        } else {
            "name: $playerName\n$yaml"
        }
    }

    private fun updatePlayerCount() {
        val count = yamlDocuments().size.coerceAtLeast(1)
        playerCountView.text = "Players: $count"
    }

    private fun generateSeed() {
        val yaml = yamlEditor.text.toString()
        if (yamlDocuments(yaml).isEmpty()) {
            status.text = "Add at least one player before generating."
            return
        }
        generateButton.isEnabled = false
        exportSeedButton.isEnabled = false
        patchButton.isEnabled = false
        status.text = "Generating ${yamlDocuments(yaml).size}-player seed… this can take a minute."
        val seed = seedEditor.text.toString()
        thread(name = "offline-seed-generation") {
            runCatching {
                val result = OfflineGenerator.generate(this, yaml, seed)
                SeedHistoryStore.add(this, result, yaml)
            }.onSuccess { entry ->
                runOnUiThread {
                    generateButton.isEnabled = true
                    openHistoryEntry(entry, loadSettings = false)
                    renderHistory()
                    val names = entry.files.joinToString("\n") { "• ${it.name}" }
                    status.text = "Generation complete · seed ${entry.seed}\n" +
                        "Players: ${entry.players.joinToString()}\n$names"
                }
            }.onFailure {
                runOnUiThread { generateButton.isEnabled = true }
                showError("Generation failed", it)
            }
        }
    }

    private fun openHistoryEntry(entry: SeedHistoryEntry, loadSettings: Boolean) {
        currentHistoryId = entry.id
        seedFile = entry.files.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }
            ?.let { File(it.path) }
            ?.takeIf { it.isFile }
        availablePatches = entry.patches.map { File(it.path) }.filter { it.isFile }
        patchFile = availablePatches.firstOrNull()
        exportSeedButton.isEnabled = seedFile != null
        hostSeedButton.isEnabled = seedFile != null && !hostingInProgress
        patchButton.isEnabled = patchFile != null
        renderPatchChoices()
        if (loadSettings) {
            historySettingsLoaded = true
            yamlEditor.setText(entry.yaml)
            yamlEditor.setSelection(0)
            seedEditor.setText(entry.seed)
            status.text = "Loaded seed ${entry.seed} · ${entry.players.joinToString()}"
        }
    }

    private fun renderPatchChoices() {
        patchesContainer.removeAllViews()
        if (availablePatches.isEmpty()) return
        patchesContainer.addView(TextView(this).apply {
            text = if (availablePatches.size == 1) {
                "ROM patch: ${availablePatches.first().name}"
            } else {
                "Choose which player's ROM to create:"
            }
        })
        if (availablePatches.size > 1) {
            availablePatches.forEach { patch ->
                patchesContainer.addView(Button(this).apply {
                    text = patch.name.removeSuffix(".apmetfus")
                    isEnabled = patch != patchFile
                    setOnClickListener {
                        patchFile = patch
                        patchButton.isEnabled = true
                        renderPatchChoices()
                        status.text = "Selected ${patch.name}"
                    }
                }, matchWrapParams())
            }
        }
    }

    private fun renderHistory() {
        historyContainer.removeAllViews()
        val entries = SeedHistoryStore.list(this)
        if (entries.isEmpty()) {
            historyContainer.addView(TextView(this).apply {
                text = "No seeds generated on this device yet."
            })
            return
        }
        entries.forEach { entry ->
            val zipAvailable = entry.files.any {
                it.name.endsWith(".zip", ignoreCase = true) && File(it.path).isFile
            }
            historyContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 20)
                addView(TextView(this@GeneratorActivity).apply {
                    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(entry.createdAt))
                    text = "$date\nSeed ${entry.seed}\n${entry.players.joinToString()} · " +
                        "${entry.patches.size} player patch${if (entry.patches.size == 1) "" else "es"}"
                    textSize = 16f
                })
                addView(Button(this@GeneratorActivity).apply {
                    text = "Open seed"
                    setOnClickListener { openHistoryEntry(entry, loadSettings = true) }
                }, matchWrapParams())
                addView(Button(this@GeneratorActivity).apply {
                    text = "Save seed ZIP"
                    isEnabled = zipAvailable
                    setOnClickListener {
                        entry.files.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }
                            ?.let { File(it.path) }
                            ?.takeIf { it.isFile }
                            ?.let { beginExport(it.name, it.readBytes()) }
                    }
                }, matchWrapParams())
                addView(Button(this@GeneratorActivity).apply {
                    text = "Host on archipelago.gg"
                    isEnabled = zipAvailable && !hostingInProgress
                    setOnClickListener {
                        entry.files.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }
                            ?.let { File(it.path) }
                            ?.takeIf { it.isFile }
                            ?.let { hostSeed(it, entry.id) }
                    }
                }, matchWrapParams())
                addView(Button(this@GeneratorActivity).apply {
                    text = "Delete from history"
                    setOnClickListener { confirmDelete(entry) }
                }, matchWrapParams())
            }, matchWrapParams())
        }
    }

    private fun hostSeed(zip: File, historyId: String?) {
        if (hostingInProgress) return
        hostingInProgress = true
        hostSeedButton.isEnabled = false
        renderHistory()
        status.text = "Uploading ${zip.name} and creating an archipelago.gg room…"
        thread(name = "archipelago-web-host") {
            runCatching { webHostClient.hostSeed(zip) }
                .onSuccess { result ->
                    historyId?.let { HostedRoomHistoryLinks.save(this, result.room.roomId, it) }
                    runOnUiThread {
                        hostingInProgress = false
                        hostSeedButton.isEnabled = seedFile?.isFile == true
                        renderHistory()
                        renderHostedRooms(result.rooms)
                        status.text = if (result.room.lastPort > 0) {
                            "Room created · connect to archipelago.gg:${result.room.lastPort}"
                        } else {
                            "Room created on archipelago.gg. It is starting; refresh hosted instances for its port."
                        }
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        hostingInProgress = false
                        hostSeedButton.isEnabled = seedFile?.isFile == true
                        renderHistory()
                    }
                    showError("Could not host seed", error)
                }
        }
    }

    private fun refreshHostedRooms() {
        refreshHostedRoomsButton.isEnabled = false
        status.text = "Refreshing hosted instances from archipelago.gg…"
        thread(name = "archipelago-web-host-refresh") {
            runCatching { webHostClient.refreshRooms() }
                .onSuccess { rooms ->
                    runOnUiThread {
                        refreshHostedRoomsButton.isEnabled = true
                        renderHostedRooms(rooms)
                        status.text = if (rooms.isEmpty()) {
                            "No hosted instances belong to this app's website session yet."
                        } else {
                            "Found ${rooms.size} hosted instance${if (rooms.size == 1) "" else "s"}."
                        }
                    }
                }
                .onFailure { error ->
                    runOnUiThread { refreshHostedRoomsButton.isEnabled = true }
                    showError("Could not refresh hosted instances", error)
                }
        }
    }

    private fun renderHostedRooms(rooms: List<HostedRoom>) {
        hostedRoomsContainer.removeAllViews()
        if (rooms.isEmpty()) {
            hostedRoomsContainer.addView(TextView(this).apply {
                text = "No hosted instances are cached on this device."
            })
            return
        }
        rooms.forEach { room ->
            hostedRoomsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 20)
                addView(TextView(this@GeneratorActivity).apply {
                    val state = when {
                        room.lastPort > 0 -> "Online · archipelago.gg:${room.lastPort}"
                        room.lastPort < 0 -> "Server error"
                        else -> "Starting or sleeping"
                    }
                    val players = room.players.takeIf { it.isNotEmpty() }?.joinToString().orEmpty()
                    val created = formatWebsiteTime(room.creationTime)
                    text = buildString {
                        append(state)
                        if (created.isNotBlank()) append("\nCreated $created")
                        if (players.isNotBlank()) append("\n$players")
                    }
                    textSize = 16f
                })
                addView(Button(this@GeneratorActivity).apply {
                    text = "Share multiplayer invite"
                    setOnClickListener { shareHostedRoom(room) }
                }, matchWrapParams())
                addView(Button(this@GeneratorActivity).apply {
                    text = "Open room controls"
                    setOnClickListener {
                        openWebUrl("${ArchipelagoWebHostClient.BASE_URL}/room/${room.roomId}")
                    }
                }, matchWrapParams())
                if (room.trackerId.isNotBlank()) {
                    addView(Button(this@GeneratorActivity).apply {
                        text = "Open tracker"
                        setOnClickListener {
                            openWebUrl("${ArchipelagoWebHostClient.BASE_URL}/tracker/${room.trackerId}")
                        }
                    }, matchWrapParams())
                }
                if (room.lastPort > 0) {
                    addView(Button(this@GeneratorActivity).apply {
                        text = "Copy server address"
                        setOnClickListener {
                            val address = "archipelago.gg:${room.lastPort}"
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Archipelago server", address))
                            status.text = "Copied $address"
                        }
                    }, matchWrapParams())
                }
            }, matchWrapParams())
        }
    }

    private fun shareHostedRoom(room: HostedRoom) {
        val entries = SeedHistoryStore.list(this).filter { entry ->
            entry.patches.isNotEmpty() && entry.patches.all { File(it.path).isFile }
        }
        if (entries.isEmpty()) {
            status.text = "No locally stored player patches are available for this room."
            return
        }

        val linkedEntry = HostedRoomHistoryLinks.historyId(this, room.roomId)?.let { linkedId ->
            entries.firstOrNull { it.id == linkedId }
        }
        if (linkedEntry != null) {
            chooseInvitePlayer(room, linkedEntry)
            return
        }

        val hostedNames = room.players.map { hostedPlayerName(it) }
        val matchingEntries = entries.filter { it.players == hostedNames }
        when {
            matchingEntries.size == 1 -> {
                HostedRoomHistoryLinks.save(this, room.roomId, matchingEntries.single().id)
                chooseInvitePlayer(room, matchingEntries.single())
            }
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
            .setMessage("The selected seed supplies the player-specific patch embedded in the invitation.")
            .setNegativeButton("Cancel", null)
            .setItems(labels) { _, index ->
                val entry = entries[index]
                HostedRoomHistoryLinks.save(this, room.roomId, entry.id)
                chooseInvitePlayer(room, entry)
            }
            .show()
    }

    private fun chooseInvitePlayer(room: HostedRoom, entry: SeedHistoryEntry) {
        val choices = entry.patches.mapIndexedNotNull { index, patch ->
            val file = File(patch.path).takeIf { it.isFile } ?: return@mapIndexedNotNull null
            val slot = Regex("(?:^|_)P(\\d+)(?:_|\\.)", RegexOption.IGNORE_CASE)
                .find(patch.name)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (index + 1)
            val playerName = entry.players.getOrNull(slot - 1) ?: "Player $slot"
            Triple(slot, playerName, file)
        }.sortedBy { it.first }
        if (choices.isEmpty()) {
            status.text = "The selected seed no longer has any stored player patches."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Share invite for which player?")
            .setItems(choices.map { "Player ${it.first} · ${it.second}" }.toTypedArray()) { _, index ->
                val (slot, playerName, patch) = choices[index]
                status.text = "Preparing $playerName's multiplayer invitation…"
                thread(name = "player-invite-package") {
                    runCatching { patch.readBytes() }
                        .onSuccess { patchBytes -> runOnUiThread {
                            runCatching {
                                RoomInvite.share(this, room, slot, playerName, patch.name, patchBytes)
                            }.onSuccess {
                                status.text = "Player-specific invitation ready for $playerName."
                            }.onFailure { showError("Could not share player invitation", it) }
                        } }
                        .onFailure { showError("Could not read ${patch.name}", it) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hostedPlayerName(displayName: String): String =
        displayName.removeSuffix(" (Metroid Fusion)")

    private fun confirmWebsiteSessionSync() {
        AlertDialog.Builder(this)
            .setTitle("Sync archipelago.gg website session?")
            .setMessage(
                "This opens the companion's secret session link in your browser. It makes this app's uploaded " +
                    "seeds and rooms appear under User Content there. Anyone who obtains that link can manage " +
                    "those instances, so do not share it.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open secret link") { _, _ -> openWebUrl(webHostClient.sessionSyncUrl()) }
            .show()
    }

    private fun openWebUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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

    private fun confirmDelete(entry: SeedHistoryEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete generated seed?")
            .setMessage("Seed ${entry.seed} and its stored ZIP and patches will be removed from this device.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                SeedHistoryStore.delete(this, entry.id)
                if (currentHistoryId == entry.id) clearSelectedSeed()
                renderHistory()
                status.text = "Deleted seed ${entry.seed} from history."
            }
            .show()
    }

    private fun clearSelectedSeed() {
        currentHistoryId = null
        seedFile = null
        patchFile = null
        availablePatches = emptyList()
        exportSeedButton.isEnabled = false
        hostSeedButton.isEnabled = false
        patchButton.isEnabled = false
        renderPatchChoices()
    }

    private fun chooseBaseRom() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
            },
            REQUEST_BASE_ROM,
        )
    }

    private fun patchBaseRom(uri: Uri) {
        val selectedPatch = patchFile ?: return
        patchButton.isEnabled = false
        status.text = "Patching ${selectedPatch.name} locally…"
        thread(name = "offline-rom-patching") {
            runCatching {
                val baseBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read the selected ROM")
                val output = File(filesDir, "offline_generator/output/${selectedPatch.nameWithoutExtension}.gba")
                OfflineGenerator.patchRom(this, selectedPatch.readBytes(), baseBytes, output)
            }.onSuccess { output ->
                runOnUiThread {
                    patchButton.isEnabled = true
                    status.text = "ROM created. Choose where to save it."
                    beginExport(output.name, output.readBytes())
                }
            }.onFailure {
                runOnUiThread { patchButton.isEnabled = true }
                showError("ROM patching failed", it)
            }
        }
    }

    private fun beginExport(name: String, bytes: ByteArray) {
        pendingExport = name to bytes
        val mimeType = when {
            name.endsWith(".yaml", true) -> "application/yaml"
            name.endsWith(".zip", true) -> "application/zip"
            else -> "application/octet-stream"
        }
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                putExtra(Intent.EXTRA_TITLE, name)
            },
            REQUEST_EXPORT,
        )
    }

    @Deprecated("Uses the platform file picker result API available to android.app.Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQUEST_BASE_ROM -> patchBaseRom(data.data!!)
            REQUEST_EXPORT -> {
                val export = pendingExport ?: return
                runCatching {
                    contentResolver.openOutputStream(data.data!!)?.use { it.write(export.second) }
                        ?: error("Could not open the selected destination")
                }.onSuccess {
                    status.text = "Saved ${export.first}"
                }.onFailure { showError("Could not save ${export.first}", it) }
                pendingExport = null
            }
        }
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

    private fun weightedButtonParams() = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
    )

    companion object {
        private const val REQUEST_BASE_ROM = 201
        private const val REQUEST_EXPORT = 202
    }
}
