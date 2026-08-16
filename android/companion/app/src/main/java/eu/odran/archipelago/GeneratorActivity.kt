package eu.odran.archipelago

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
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
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
    private lateinit var forgetBaseRomButton: Button
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
    private var currentTemplateGame = ""
    private var templateLoadInProgress = false

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
        val gameTemplateButton = Button(this).apply {
            text = "Change player game"
            setOnClickListener { chooseGameTemplate() }
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
            setOnClickListener { patchWithCachedBaseRomOrChoose() }
        }
        forgetBaseRomButton = Button(this).apply {
            text = "Forget cached base ROM"
            isEnabled = false
            setOnClickListener {
                val game = selectedPatchGame()
                if (BaseRomCache.forget(this@GeneratorActivity, game)) {
                    isEnabled = false
                    status.text = "Forgot the cached $game base ROM. It will be requested next time."
                } else {
                    status.text = "Could not remove the cached base ROM."
                }
            }
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
                text = "Offline GBA Archipelago Generator"
                textSize = 24f
            })
            addView(TextView(this@GeneratorActivity).apply {
                text = "Each YAML document is one player. Add player asks which game that player will use; " +
                    "Change player game replaces only the selected player's template. You can then edit each " +
                    "document's settings independently. Generation and patching need no network connection. " +
                    "The first valid base ROM you " +
                    "select is copied into app-private storage and reused for later seeds. It is removed if you " +
                    "uninstall the app or tap Forget cached base ROM."
            })
            addView(Button(this@GeneratorActivity).apply {
                text = "Manage installed APWorlds"
                setOnClickListener {
                    startActivity(Intent(this@GeneratorActivity, ApWorldManagerActivity::class.java))
                }
            }, matchWrapParams())
            addView(gameTemplateButton, matchWrapParams())
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
            addView(forgetBaseRomButton, matchWrapParams())
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
                setOnClickListener {
                    openAuthenticatedWebUrl(
                        "${ArchipelagoWebHostClient.BASE_URL}/user-content",
                        "Hosted instances",
                    )
                }
            }, matchWrapParams())
            addView(hostedRoomsContainer, matchWrapParams())
            addView(TextView(this@GeneratorActivity).apply {
                text = "Generated seed history"
                textSize = 22f
                setPadding(0, 32, 0, 8)
            })
            addView(historyContainer, matchWrapParams())
        }
        val scrollView = ScrollView(this).apply { addView(content) }
        SystemBarInsets.apply(window, scrollView)
        setContentView(scrollView)
        renderHostedRooms(webHostClient.cachedRooms())
        renderHistory()

        thread(name = "offline-generator-startup") {
            runCatching {
                val catalog = OfflineGenerator.refreshCatalog(this)
                val firstGame = catalog.firstOrNull { it.source == "imported" && it.template }?.game
                Triple(firstGame?.let { OfflineGenerator.defaultYaml(this, it) }, firstGame, catalog)
            }
                .onSuccess { (template, firstGame, catalog) ->
                    runOnUiThread {
                        if (firstGame != null) currentTemplateGame = firstGame
                        if (!historySettingsLoaded) yamlEditor.setText(template.orEmpty())
                        yamlEditor.isEnabled = true
                        generateButton.isEnabled = template != null
                        val imported = catalog.count { it.source == "imported" }
                        status.text = if (template == null) {
                            "No game APWorlds installed. Open Manage installed APWorlds and import a trusted .apworld."
                        } else {
                            "Ready · ${catalog.size} worlds loaded · $imported imported"
                        }
                    }
                }
                .onFailure { showError("Could not start the offline generator", it) }
        }
    }

    private fun chooseGameTemplate() {
        if (!yamlEditor.isEnabled || templateLoadInProgress) return
        val documents = yamlDocuments()
        if (documents.isEmpty()) {
            chooseGame("Choose the first player's game") { game -> addPlayerWithGame(game) }
            return
        }
        if (documents.size == 1) {
            chooseGameForPlayer(0, documents)
            return
        }
        val players = documents.mapIndexed { index, document ->
            val name = playerNameFromYaml(document) ?: "Player ${index + 1}"
            val game = gameFromYaml(document) ?: "unknown game"
            "${index + 1} · $name · $game"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Change which player's game?")
            .setItems(players) { _, index -> chooseGameForPlayer(index, documents) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseGameForPlayer(index: Int, documents: List<String>) {
        val document = documents.getOrNull(index) ?: return
        val playerName = playerNameFromYaml(document) ?: "Player ${index + 1}"
        val currentGame = gameFromYaml(document)
        chooseGame("Choose $playerName's game") { game ->
            if (game == currentGame) {
                status.text = "$playerName already uses $game. Its settings were left unchanged."
            } else {
                replacePlayerGame(index, documents, playerName, game)
            }
        }
    }

    private fun chooseGame(title: String, onSelected: (String) -> Unit) {
        val games = OfflineGenerator.availableGames(this).toTypedArray()
        if (games.isEmpty()) {
            status.text = "Install a trusted game .apworld first."
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(games) { _, index -> onSelected(games[index]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun replacePlayerGame(
        index: Int,
        documents: List<String>,
        playerName: String,
        game: String,
    ) {
        loadPlayerTemplate(game, "Loading $game options for $playerName…") { template ->
            val updated = documents.toMutableList()
            updated[index] = replacePlayerName(template, playerName)
            applyPlayerDocuments(updated, game)
            status.text = "$playerName now uses $game. Other player YAML documents were preserved."
        }
    }

    private fun addPlayerWithGame(game: String) {
        val documents = yamlDocuments()
        val playerName = nextPlayerName(documents)
        loadPlayerTemplate(game, "Loading $game options for $playerName…") { template ->
            applyPlayerDocuments(documents + replacePlayerName(template, playerName), game)
            yamlEditor.setSelection(yamlEditor.text.length)
            status.text = "Added $playerName using $game."
        }
    }

    private fun loadPlayerTemplate(game: String, message: String, onLoaded: (String) -> Unit) {
        templateLoadInProgress = true
        yamlEditor.isEnabled = false
        generateButton.isEnabled = false
        status.text = message
        thread(name = "offline-player-game-template") {
            runCatching { OfflineGenerator.defaultYaml(this, game) }
                .onSuccess { template -> runOnUiThread {
                    templateLoadInProgress = false
                    yamlEditor.isEnabled = true
                    generateButton.isEnabled = true
                    onLoaded(template)
                } }
                .onFailure { error ->
                    runOnUiThread {
                        templateLoadInProgress = false
                        yamlEditor.isEnabled = true
                        generateButton.isEnabled = true
                    }
                    showError("Could not load the $game template", error)
                }
        }
    }

    private fun applyPlayerDocuments(documents: List<String>, mostRecentGame: String) {
        currentTemplateGame = mostRecentGame
        clearSelectedSeed()
        historySettingsLoaded = false
        yamlEditor.setText(documents.joinToString("\n---\n"))
        forgetBaseRomButton.isEnabled = BaseRomCache.isPresent(this, mostRecentGame)
    }

    private fun addPlayer() {
        if (!yamlEditor.isEnabled || templateLoadInProgress) return
        chooseGame("Choose the new player's game") { game -> addPlayerWithGame(game) }
    }

    private fun removeLastPlayer() {
        if (!yamlEditor.isEnabled || templateLoadInProgress) return
        val documents = yamlDocuments()
        if (documents.size <= 1) {
            status.text = "A seed needs at least one player."
            return
        }
        val remaining = documents.dropLast(1)
        clearSelectedSeed()
        historySettingsLoaded = false
        currentTemplateGame = gameFromYaml(remaining.last()) ?: currentTemplateGame
        yamlEditor.setText(remaining.joinToString("\n---\n"))
        yamlEditor.setSelection(yamlEditor.text.length)
        forgetBaseRomButton.isEnabled = BaseRomCache.isPresent(this, currentTemplateGame)
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

    private fun playerNameFromYaml(yaml: String): String? = Regex("(?m)^name\\s*:\\s*(.+?)\\s*$")
        .find(yaml)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.removeSurrounding("\"")
        ?.removeSurrounding("'")
        ?.takeIf { it.isNotBlank() }

    private fun nextPlayerName(documents: List<String>): String {
        val existing = documents.mapNotNull(::playerNameFromYaml).map { it.lowercase(Locale.ROOT) }.toSet()
        var number = documents.size + 1
        while ("player $number" in existing) number += 1
        return "Player $number"
    }

    private fun updatePlayerCount() {
        val count = yamlDocuments().size.coerceAtLeast(1)
        playerCountView.text = "Players: $count"
    }

    private fun gameFromYaml(yaml: String): String? = Regex("(?m)^game\\s*:\\s*(.+?)\\s*$")
        .find(yaml)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.removeSurrounding("\"")
        ?.removeSurrounding("'")
        ?.takeIf { it in OfflineGenerator.availableGames(this) }

    private fun selectedPatchGame(): String = patchFile?.let(::gameFromPatchFile) ?: currentTemplateGame

    private fun canPatchSelectedRom(): Boolean {
        if (patchFile == null) return false
        val game = selectedPatchGame()
        return OfflineGenerator.cachedCatalog().any { it.game == game && it.romPatch }
    }

    private fun gameFromPatchFile(patch: File): String? = runCatching {
        ZipFile(patch).use { archive ->
            if (archive.getEntry("patch_file.json") != null) return@use "Metroid Fusion"
            val manifest = archive.getEntry("archipelago.json") ?: return@use null
            archive.getInputStream(manifest).bufferedReader(Charsets.UTF_8).use { reader ->
                JSONObject(reader.readText()).optString("game").takeIf { it.isNotBlank() }
            }
        }
    }.getOrNull()

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
        currentTemplateGame = gameFromYaml(entry.yaml) ?: selectedPatchGame()
        exportSeedButton.isEnabled = seedFile != null
        hostSeedButton.isEnabled = seedFile != null && !hostingInProgress
        patchButton.isEnabled = canPatchSelectedRom()
        forgetBaseRomButton.isEnabled = BaseRomCache.isPresent(this, selectedPatchGame())
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
            val heading = if (availablePatches.size == 1) {
                "ROM patch: ${availablePatches.first().name}"
            } else {
                "Choose which player's ROM to create:"
            }
            text = if (canPatchSelectedRom()) heading else "$heading\nThis world's output is export-only in the GBA companion."
        })
        if (availablePatches.size > 1) {
            availablePatches.forEach { patch ->
                patchesContainer.addView(Button(this).apply {
                    text = patch.nameWithoutExtension
                    isEnabled = patch != patchFile
                    setOnClickListener {
                        patchFile = patch
                        patchButton.isEnabled = canPatchSelectedRom()
                        forgetBaseRomButton.isEnabled = BaseRomCache.isPresent(
                            this@GeneratorActivity,
                            selectedPatchGame(),
                        )
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
                        openAuthenticatedWebUrl(
                            "${ArchipelagoWebHostClient.BASE_URL}/room/${room.roomId}",
                            "Room controls",
                        )
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
        displayName.replace(Regex(" \\([^()]+\\)$"), "")

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

    private fun patchWithCachedBaseRomOrChoose() {
        val selectedPatch = patchFile ?: return
        val game = selectedPatchGame()
        patchButton.isEnabled = false
        status.text = "Checking for a cached $game base ROM…"
        thread(name = "offline-rom-cache-check") {
            val cachedRom = BaseRomCache.load(this, game)
            if (cachedRom == null) {
                runOnUiThread {
                    patchButton.isEnabled = true
                    forgetBaseRomButton.isEnabled = false
                    status.text = "Select your clean $game base ROM. It will be cached privately for later seeds."
                    chooseBaseRom()
                }
            } else {
                runOnUiThread {
                    forgetBaseRomButton.isEnabled = true
                    status.text = "Using the cached base ROM to patch ${selectedPatch.name}…"
                }
                patchBaseRom(selectedPatch, cachedRom)
            }
        }
    }

    private fun patchBaseRom(uri: Uri) {
        val selectedPatch = patchFile ?: return
        val game = selectedPatchGame()
        patchButton.isEnabled = false
        status.text = "Validating and caching the selected base ROM…"
        thread(name = "offline-rom-patching") {
            runCatching {
                val selectedBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not read the selected ROM")
                val baseBytes = if (BaseRomCache.hasBuiltInValidation(game)) {
                    BaseRomCache.store(this, selectedBytes, game)
                } else {
                    selectedBytes
                }
                val output = createPatchedRom(selectedPatch, baseBytes)
                if (!BaseRomCache.hasBuiltInValidation(game)) {
                    BaseRomCache.storeAfterSuccessfulPatch(this, selectedBytes, game)
                }
                output
            }.onSuccess { output ->
                runOnUiThread {
                    patchButton.isEnabled = true
                    forgetBaseRomButton.isEnabled = true
                    status.text = "ROM created and base ROM cached. Choose where to save it."
                    beginExport(output.name, output.readBytes())
                }
            }.onFailure {
                runOnUiThread { patchButton.isEnabled = true }
                showError("ROM patching failed", it)
            }
        }
    }

    private fun patchBaseRom(selectedPatch: File, baseBytes: ByteArray) {
        thread(name = "offline-rom-patching") {
            runCatching { createPatchedRom(selectedPatch, baseBytes) }
                .onSuccess { output -> runOnUiThread {
                    patchButton.isEnabled = true
                    forgetBaseRomButton.isEnabled = true
                    status.text = "ROM created using the cached base ROM. Choose where to save it."
                    beginExport(output.name, output.readBytes())
                } }
                .onFailure {
                    if (!BaseRomCache.hasBuiltInValidation(selectedPatchGame())) {
                        BaseRomCache.forget(this, selectedPatchGame())
                    }
                    runOnUiThread { patchButton.isEnabled = true }
                    showError("ROM patching failed", it)
                }
        }
    }

    private fun createPatchedRom(selectedPatch: File, baseBytes: ByteArray): File {
        val output = File(filesDir, "offline_generator/output/${selectedPatch.nameWithoutExtension}.gba")
        return OfflineGenerator.patchRom(this, selectedPatch.readBytes(), baseBytes, output)
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
        if (resultCode != RESULT_OK || data?.data == null) {
            if (requestCode == REQUEST_BASE_ROM) patchButton.isEnabled = canPatchSelectedRom()
            return
        }
        when (requestCode) {
            REQUEST_BASE_ROM -> patchBaseRom(data.data!!)
            REQUEST_EXPORT -> {
                val export = pendingExport ?: return
                val destination = data.data!!
                runCatching {
                    contentResolver.openOutputStream(destination)?.use { it.write(export.second) }
                        ?: error("Could not open the selected destination")
                }.onSuccess {
                    status.text = "Saved ${export.first}"
                    if (export.first.endsWith(".gba", ignoreCase = true)) {
                        offerRetroArchLaunch(export.first, destination)
                    }
                }.onFailure { showError("Could not save ${export.first}", it) }
                pendingExport = null
            }
        }
    }

    private fun offerRetroArchLaunch(name: String, uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("ROM ready")
            .setMessage("Saved $name. Launch it now in RetroArch with the custom mGBA Archipelago core?")
            .setNegativeButton("Done", null)
            .setPositiveButton("Launch RetroArch") { _, _ ->
                runCatching { RetroArchLauncher.launch(this, uri) }
                    .onSuccess { status.text = "Launching $name in RetroArch…" }
                    .onFailure { showError("Could not launch RetroArch", it) }
            }
            .show()
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
