package eu.odran.archipelago

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.concurrent.thread

private data class GeneratorStartupState(
    val forms: List<PlayerFormData>,
    val schemas: Map<String, GameOptionSchema>,
    val catalog: List<WorldCapability>,
    val draft: GeneratorDraft?,
)

private data class ImportedYamlState(
    val entry: SavedYamlEntry,
    val yaml: String,
    val forms: List<PlayerFormData>,
    val schemas: Map<String, GameOptionSchema>,
)

/** Creates player YAMLs, generates seeds, and patches a user-supplied ROM entirely offline. */
class GeneratorActivity : Activity() {
    private lateinit var yamlEditor: EditText
    private lateinit var seedEditor: EditText
    private lateinit var playerCountView: TextView
    private lateinit var playerSelector: Spinner
    private lateinit var playerNameEditor: EditText
    private lateinit var playerOptionsContainer: LinearLayout
    private lateinit var screenScrollView: ScrollView
    private lateinit var generateButton: Button
    private lateinit var exportSeedButton: Button
    private lateinit var hostSeedButton: Button
    private lateinit var patchButton: Button
    private lateinit var forgetBaseRomButton: Button
    private lateinit var patchesContainer: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyToggleButton: Button
    private lateinit var savedYamlsContainer: LinearLayout
    private lateinit var savedYamlsToggleButton: Button
    private lateinit var rememberYamlButton: Button
    private lateinit var importYamlButton: Button
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
    private val playerForms = mutableListOf<PlayerFormData>()
    private val optionSchemas = mutableMapOf<String, GameOptionSchema>()
    private var selectedPlayerIndex = 0
    private var renderingPlayer = false
    private var renderingAdvancedYaml = false
    private var advancedYamlDirty = false
    private var draftReady = false
    private var historyEntryCount = 0
    private var savedYamlEntryCount = 0
    private var generatorReady = false
    private var savedYamlMetadataRefreshInProgress = false
    private var pendingRomRequirements: RomRequirements? = null
    private val pendingRomInputs = linkedMapOf<String, ByteArray>()
    private val pendingRomInputUris = linkedMapOf<String, Uri>()
    private var pendingStreamingPatch: Pair<File, Map<String, Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webHostClient = ArchipelagoWebHostClient(this)

        yamlEditor = EditText(this).apply {
            minLines = 12
            maxLines = 20
            textSize = 13f
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
            text = "Players: 0"
            CompanionUi.styleBody(this)
        }
        playerSelector = Spinner(this)
        playerNameEditor = EditText(this).apply {
            hint = "Player name"
            setSingleLine(true)
            isEnabled = false
        }
        playerOptionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        status = TextView(this).apply {
            text = "Starting Python 3.12…"
            minLines = 4
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            CompanionUi.styleBody(this)
        }
        patchesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        historyToggleButton = Button(this).apply {
            text = "Show saved seeds"
            CompanionUi.styleQuiet(this)
            setOnClickListener {
                preserveScrollPosition {
                    historyContainer.visibility = if (historyContainer.visibility == View.VISIBLE) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                    updateHistoryToggleLabel()
                }
            }
        }
        savedYamlsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        savedYamlsToggleButton = Button(this).apply {
            text = "Show saved YAMLs"
            CompanionUi.styleQuiet(this)
            setOnClickListener {
                preserveScrollPosition {
                    savedYamlsContainer.visibility = if (savedYamlsContainer.visibility == View.VISIBLE) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                    updateSavedYamlsToggleLabel()
                }
            }
        }

        val addPlayerButton = Button(this).apply {
            text = "Add player"
            CompanionUi.styleSecondary(this)
            setOnClickListener { addPlayer() }
        }
        val removePlayerButton = Button(this).apply {
            text = "Remove player"
            CompanionUi.styleQuiet(this)
            setOnClickListener { removeSelectedPlayer() }
        }
        val playerButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(addPlayerButton, weightedButtonParams())
            addView(removePlayerButton, weightedButtonParams())
        }
        val resetDraftButton = Button(this).apply {
            text = "Reset all generator settings"
            CompanionUi.styleQuiet(this)
            setOnClickListener {
                AlertDialog.Builder(this@GeneratorActivity)
                    .setTitle("Reset generator settings?")
                    .setMessage("All player names, game options, seed, and advanced YAML edits will return to defaults.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Reset") { _, _ ->
                        draftReady = false
                        GeneratorDraftStore.clear(this@GeneratorActivity)
                        recreate()
                    }
                    .show()
            }
        }
        val saveYamlButton = Button(this).apply {
            text = "Save player YAML"
            CompanionUi.styleQuiet(this)
            setOnClickListener { exportPlayerYaml() }
        }
        rememberYamlButton = Button(this).apply {
            text = "Remember current YAML"
            CompanionUi.styleSecondary(this)
            isEnabled = false
            setOnClickListener { rememberCurrentYaml() }
        }
        importYamlButton = Button(this).apply {
            text = "Import YAML file"
            CompanionUi.styleQuiet(this)
            isEnabled = false
            setOnClickListener { openYamlPicker() }
        }
        val applyYamlButton = Button(this).apply {
            text = "Apply YAML to form"
            CompanionUi.styleSecondary(this)
            setOnClickListener { applyAdvancedYaml() }
        }
        val discardYamlButton = Button(this).apply {
            text = "Discard YAML edits"
            CompanionUi.styleQuiet(this)
            setOnClickListener {
                advancedYamlDirty = false
                refreshAdvancedYaml()
            }
        }
        val advancedYamlContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(yamlEditor, matchWrapParams())
            addView(applyYamlButton, CompanionUi.insetTop(applyYamlButton, this@GeneratorActivity, 6))
            addView(discardYamlButton, CompanionUi.insetTop(discardYamlButton, this@GeneratorActivity, 4))
            addView(saveYamlButton, CompanionUi.insetTop(saveYamlButton, this@GeneratorActivity, 4))
        }
        val advancedYamlButton = Button(this).apply {
            text = "Show advanced YAML"
            CompanionUi.styleQuiet(this)
            setOnClickListener {
                if (advancedYamlContainer.visibility == View.VISIBLE) {
                    preserveScrollPosition {
                        advancedYamlContainer.visibility = View.GONE
                        text = "Show advanced YAML"
                    }
                } else if (advancedYamlDirty) {
                    preserveScrollPosition {
                        advancedYamlContainer.visibility = View.VISIBLE
                        text = "Hide advanced YAML"
                    }
                } else {
                    refreshAdvancedYaml {
                        preserveScrollPosition {
                            advancedYamlContainer.visibility = View.VISIBLE
                            text = "Hide advanced YAML"
                        }
                    }
                }
            }
        }
        val gameTemplateButton = Button(this).apply {
            text = "Change player game"
            CompanionUi.styleSecondary(this)
            setOnClickListener { chooseGameTemplate() }
        }
        generateButton = Button(this).apply {
            text = "Generate offline"
            CompanionUi.stylePrimary(this)
            isEnabled = false
            setOnClickListener { generateSeed() }
        }
        exportSeedButton = Button(this).apply {
            text = "Save seed ZIP"
            CompanionUi.styleSecondary(this)
            isEnabled = false
            setOnClickListener {
                seedFile?.takeIf { it.isFile }?.let { beginExport(it.name, it.readBytes()) }
            }
        }
        hostSeedButton = Button(this).apply {
            text = "Host seed on archipelago.gg"
            CompanionUi.styleSecondary(this)
            isEnabled = false
            setOnClickListener { seedFile?.let { hostSeed(it, currentHistoryId) } }
        }
        val viewHostedRoomsButton = Button(this).apply {
            text = "View hosted rooms"
            CompanionUi.styleQuiet(this)
            setOnClickListener {
                startActivity(Intent(this@GeneratorActivity, HostedRoomsActivity::class.java))
            }
        }
        patchButton = Button(this).apply {
            text = "Create patched ROM"
            CompanionUi.stylePrimary(this)
            isEnabled = false
            setOnClickListener { patchWithCachedBaseRomOrChoose() }
        }
        forgetBaseRomButton = Button(this).apply {
            text = "Forget cached base ROM"
            CompanionUi.styleDanger(this)
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
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (!renderingAdvancedYaml) advancedYamlDirty = true
            }

            override fun afterTextChanged(text: Editable?) = Unit
        })
        playerNameEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (renderingPlayer) return
                playerForms.getOrNull(selectedPlayerIndex)?.let { player ->
                    player.name = text?.toString().orEmpty()
                    markFormChanged()
                    (playerSelector.selectedView as? TextView)?.text =
                        "${selectedPlayerIndex + 1} · ${player.name.ifBlank { "Unnamed player" }} · ${player.game}"
                }
            }
            override fun afterTextChanged(text: Editable?) = Unit
        })
        playerSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position !in playerForms.indices || position == selectedPlayerIndex && renderingPlayer) return
                selectedPlayerIndex = position
                renderSelectedPlayer()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val manageWorldsButton = Button(this).apply {
            text = "Game worlds"
            CompanionUi.styleQuiet(this)
            setOnClickListener {
                startActivity(Intent(this@GeneratorActivity, ApWorldManagerActivity::class.java))
            }
        }
        val content = CompanionUi.screen(this).apply {
            addView(CompanionUi.pageTitle(
                this@GeneratorActivity,
                "Seed generator",
                "Configure players, generate offline, then patch or host the result.",
            ), CompanionUi.fullWidth())

            addView(CompanionUi.card(this@GeneratorActivity, "Generator status").apply {
                addView(status, matchWrapParams())
            }, CompanionUi.cardParams(this@GeneratorActivity))

            addView(CompanionUi.card(
                this@GeneratorActivity,
                "Players and seed",
                "Select a player to edit, add another player, or change the selected game.",
            ).apply {
                addView(manageWorldsButton, matchWrapParams())
                addView(playerSelector, CompanionUi.insetTop(playerSelector, this@GeneratorActivity, 8))
                addView(gameTemplateButton, CompanionUi.insetTop(gameTemplateButton, this@GeneratorActivity, 4))
                addView(playerCountView, CompanionUi.insetTop(playerCountView, this@GeneratorActivity, 8))
                addView(playerButtons, CompanionUi.insetTop(playerButtons, this@GeneratorActivity, 4))
                addView(seedEditor, CompanionUi.insetTop(seedEditor, this@GeneratorActivity, 8))
                addView(resetDraftButton, CompanionUi.insetTop(resetDraftButton, this@GeneratorActivity, 4))
            }, CompanionUi.cardParams(this@GeneratorActivity))

            addView(CompanionUi.card(
                this@GeneratorActivity,
                "Player settings",
                "Options are generated from the selected APWorld. Changes become YAML when you generate or save.",
            ).apply {
                addView(playerNameEditor, matchWrapParams())
                addView(playerOptionsContainer, CompanionUi.insetTop(playerOptionsContainer, this@GeneratorActivity, 8))
            }, CompanionUi.cardParams(this@GeneratorActivity))

            addView(CompanionUi.card(
                this@GeneratorActivity,
                "Advanced YAML",
                "Optional fallback for custom or unsupported settings. Apply edits to bring them back into the form.",
            ).apply {
                addView(advancedYamlButton, matchWrapParams())
                addView(advancedYamlContainer, CompanionUi.insetTop(advancedYamlContainer, this@GeneratorActivity, 6))
            }, CompanionUi.cardParams(this@GeneratorActivity))

            addView(CompanionUi.card(
                this@GeneratorActivity,
                "Saved YAML configurations",
                "Remember or import reusable player settings. Saved YAMLs also appear read-only in compatible file managers.",
            ).apply {
                addView(rememberYamlButton, matchWrapParams())
                addView(importYamlButton, CompanionUi.insetTop(importYamlButton, this@GeneratorActivity, 4))
                addView(savedYamlsToggleButton, CompanionUi.insetTop(savedYamlsToggleButton, this@GeneratorActivity, 4))
                addView(savedYamlsContainer, matchWrapParams())
            }, CompanionUi.cardParams(this@GeneratorActivity))

            addView(CompanionUi.card(
                this@GeneratorActivity,
                "Generate and play",
                "Generation and ROM patching work offline. Hosting requires an internet connection.",
            ).apply {
                addView(generateButton, matchWrapParams())
                addView(exportSeedButton, CompanionUi.insetTop(exportSeedButton, this@GeneratorActivity, 4))
                addView(hostSeedButton, CompanionUi.insetTop(hostSeedButton, this@GeneratorActivity, 4))
                addView(viewHostedRoomsButton, CompanionUi.insetTop(viewHostedRoomsButton, this@GeneratorActivity, 4))
                addView(patchesContainer, CompanionUi.insetTop(patchesContainer, this@GeneratorActivity, 8))
                addView(patchButton, CompanionUi.insetTop(patchButton, this@GeneratorActivity, 4))
                addView(forgetBaseRomButton, CompanionUi.insetTop(forgetBaseRomButton, this@GeneratorActivity, 4))
            }, CompanionUi.cardParams(this@GeneratorActivity))

            addView(CompanionUi.card(
                this@GeneratorActivity,
                "Seed history",
                "Reopen, export, host, or delete seeds generated on this device.",
            ).apply {
                addView(historyToggleButton, matchWrapParams())
                addView(historyContainer, matchWrapParams())
            }, CompanionUi.cardParams(this@GeneratorActivity))
        }
        screenScrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        SystemBarInsets.apply(window, screenScrollView)
        setContentView(screenScrollView)
        renderSavedYamls()
        renderHistory()

        val savedDraft = GeneratorDraftStore.load(this)
        thread(name = "offline-generator-startup") {
            runCatching {
                val catalog = OfflineGenerator.refreshCatalog(this)
                val restoredForms = savedDraft?.let { draft ->
                    runCatching {
                        OfflineGenerator.decodePlayerForms(draft.playersJson).also { forms ->
                            require(forms.isNotEmpty()) { "The saved generator draft has no players" }
                            val availableGames = OfflineGenerator.availableGames(this).toSet()
                            require(forms.all { it.game in availableGames }) {
                                "A game used by the saved generator draft is no longer installed"
                            }
                        }
                    }.getOrNull()
                }
                val forms = restoredForms ?: run {
                    val firstGame = (
                        catalog.firstOrNull { it.source == "imported" && it.template }
                            ?: catalog.firstOrNull { it.source == "bundled" && it.template }
                        )?.game
                    val template = firstGame?.let { OfflineGenerator.defaultYaml(this, it) }
                    template?.let { OfflineGenerator.playerFormsFromYaml(this, it) }.orEmpty()
                }
                val schemas = forms.map { it.game }.distinct().associateWith {
                    OfflineGenerator.optionSchema(this, it)
                }
                GeneratorStartupState(forms, schemas, catalog, savedDraft.takeIf { restoredForms != null })
            }
                .onSuccess { startup ->
                    runOnUiThread {
                        if (!historySettingsLoaded) {
                            applyPlayerForms(
                                startup.forms,
                                startup.schemas,
                                startup.draft?.selectedPlayerIndex ?: 0,
                            )
                            startup.draft?.let(::restoreDraftFields)
                        }
                        yamlEditor.isEnabled = startup.forms.isNotEmpty()
                        generateButton.isEnabled = startup.forms.isNotEmpty()
                        generatorReady = startup.forms.isNotEmpty()
                        rememberYamlButton.isEnabled = generatorReady
                        importYamlButton.isEnabled = generatorReady
                        renderSavedYamls()
                        backfillSavedYamlMetadata()
                        val imported = startup.catalog.count { it.source == "imported" }
                        val bundled = startup.catalog.count { it.source == "bundled" }
                        status.text = if (startup.forms.isEmpty()) {
                            "No compatible game worlds loaded. Open Game worlds for details."
                        } else if (startup.draft != null) {
                            "Restored generator draft · $bundled built in · $imported imported"
                        } else {
                            "Ready · $bundled built in · $imported imported"
                        }
                    }
                }
                .onFailure { showError("Could not start the offline generator", it) }
        }
    }

    private fun chooseGameTemplate() {
        if (templateLoadInProgress) return
        val player = playerForms.getOrNull(selectedPlayerIndex)
        if (player == null) {
            chooseGame("Choose the first player's game") { game -> addPlayerWithGame(game) }
            return
        }
        chooseGame("Choose ${player.name.ifBlank { "Player ${selectedPlayerIndex + 1}" }}'s game") { game ->
            if (game == player.game) {
                status.text = "This player already uses $game. The settings were left unchanged."
            } else {
                replacePlayerGame(selectedPlayerIndex, player.name, game)
            }
        }
    }

    override fun onPause() {
        saveGeneratorDraft()
        super.onPause()
    }

    private fun saveGeneratorDraft() {
        if (!draftReady || playerForms.isEmpty()) return
        GeneratorDraftStore.save(
            this,
            GeneratorDraft(
                playersJson = OfflineGenerator.encodePlayerForms(playerForms),
                selectedPlayerIndex = selectedPlayerIndex,
                seed = seedEditor.text.toString(),
                advancedYaml = yamlEditor.text.toString(),
                advancedYamlDirty = advancedYamlDirty,
            ),
        )
    }

    private fun restoreDraftFields(draft: GeneratorDraft) {
        seedEditor.setText(draft.seed)
        renderingAdvancedYaml = true
        yamlEditor.setText(draft.advancedYaml)
        renderingAdvancedYaml = false
        advancedYamlDirty = draft.advancedYamlDirty
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
        playerName: String,
        game: String,
    ) {
        loadPlayerForm(game, "Loading $game options for $playerName…") { form, schema ->
            form.name = playerName
            playerForms[index] = form
            optionSchemas[game] = schema
            selectedPlayerIndex = index
            markFormChanged()
            refreshPlayerSelector()
            renderSelectedPlayer()
            status.text = "$playerName now uses $game. The other players were preserved."
        }
    }

    private fun addPlayerWithGame(game: String) {
        val playerName = nextPlayerName()
        loadPlayerForm(game, "Loading $game options for $playerName…") { form, schema ->
            form.name = playerName
            playerForms += form
            optionSchemas[game] = schema
            selectedPlayerIndex = playerForms.lastIndex
            markFormChanged()
            refreshPlayerSelector()
            renderSelectedPlayer()
            status.text = "Added $playerName using $game."
        }
    }

    private fun loadPlayerForm(
        game: String,
        message: String,
        onLoaded: (PlayerFormData, GameOptionSchema) -> Unit,
    ) {
        templateLoadInProgress = true
        generateButton.isEnabled = false
        status.text = message
        thread(name = "offline-player-game-template") {
            runCatching {
                val template = OfflineGenerator.defaultYaml(this, game)
                val form = OfflineGenerator.playerFormsFromYaml(this, template).first()
                form to OfflineGenerator.optionSchema(this, game)
            }
                .onSuccess { (form, schema) -> runOnUiThread {
                    templateLoadInProgress = false
                    generateButton.isEnabled = true
                    onLoaded(form, schema)
                } }
                .onFailure { error ->
                    runOnUiThread {
                        templateLoadInProgress = false
                        generateButton.isEnabled = playerForms.isNotEmpty()
                    }
                    showError("Could not load the $game options", error)
                }
        }
    }

    private fun applyPlayerForms(
        forms: List<PlayerFormData>,
        schemas: Map<String, GameOptionSchema>,
        selectedIndex: Int,
        clearGeneratedSeed: Boolean = true,
    ) {
        playerForms.clear()
        playerForms.addAll(forms)
        optionSchemas.putAll(schemas)
        selectedPlayerIndex = selectedIndex.coerceIn(0, playerForms.lastIndex.coerceAtLeast(0))
        currentTemplateGame = playerForms.getOrNull(selectedPlayerIndex)?.game.orEmpty()
        if (clearGeneratedSeed) clearSelectedSeed()
        historySettingsLoaded = false
        advancedYamlDirty = false
        renderingAdvancedYaml = true
        yamlEditor.setText("")
        renderingAdvancedYaml = false
        refreshPlayerSelector()
        renderSelectedPlayer()
        generateButton.isEnabled = playerForms.isNotEmpty() && !templateLoadInProgress
        yamlEditor.isEnabled = playerForms.isNotEmpty()
        forgetBaseRomButton.isEnabled = BaseRomCache.isPresent(this, currentTemplateGame)
        draftReady = playerForms.isNotEmpty()
    }

    private fun addPlayer() {
        if (templateLoadInProgress) return
        chooseGame("Choose the new player's game") { game -> addPlayerWithGame(game) }
    }

    private fun removeSelectedPlayer() {
        if (templateLoadInProgress) return
        if (playerForms.size <= 1) {
            status.text = "A seed needs at least one player."
            return
        }
        val removed = playerForms.removeAt(selectedPlayerIndex)
        selectedPlayerIndex = selectedPlayerIndex.coerceAtMost(playerForms.lastIndex)
        markFormChanged()
        refreshPlayerSelector()
        renderSelectedPlayer()
        status.text = "Removed ${removed.name.ifBlank { "the selected player" }}."
    }

    private fun nextPlayerName(): String {
        val existing = playerForms.map { it.name.lowercase(Locale.ROOT) }.toSet()
        var number = playerForms.size + 1
        while ("player $number" in existing) number += 1
        return "Player $number"
    }

    private fun markFormChanged() {
        clearSelectedSeed()
        historySettingsLoaded = false
        currentTemplateGame = playerForms.getOrNull(selectedPlayerIndex)?.game.orEmpty()
        forgetBaseRomButton.isEnabled = BaseRomCache.isPresent(this, currentTemplateGame)
    }

    private fun refreshPlayerSelector() {
        val labels = playerForms.mapIndexed { index, player ->
            "${index + 1} · ${player.name.ifBlank { "Unnamed player" }} · ${player.game}"
        }
        renderingPlayer = true
        playerSelector.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        if (playerForms.isNotEmpty()) playerSelector.setSelection(selectedPlayerIndex, false)
        renderingPlayer = false
        playerCountView.text = "Players: ${playerForms.size}"
        playerSelector.isEnabled = playerForms.isNotEmpty()
    }

    private fun renderSelectedPlayer() = preserveScrollPosition { renderSelectedPlayerContent() }

    private fun renderSelectedPlayerContent() {
        renderingPlayer = true
        playerOptionsContainer.removeAllViews()
        val player = playerForms.getOrNull(selectedPlayerIndex)
        playerNameEditor.isEnabled = player != null
        playerNameEditor.setText(player?.name.orEmpty())
        if (player == null) {
            playerOptionsContainer.addView(TextView(this).apply {
                text = "No compatible game world is available."
                CompanionUi.styleMuted(this)
            })
            renderingPlayer = false
            return
        }
        currentTemplateGame = player.game
        val schema = optionSchemas[player.game]
        if (schema == null) {
            playerOptionsContainer.addView(TextView(this).apply {
                text = "The option description for ${player.game} is not loaded."
                CompanionUi.styleMuted(this)
            })
        } else {
            schema.groups.forEachIndexed { index, group ->
                playerOptionsContainer.addView(
                    optionGroupView(player, group, collapsed = group.startCollapsed || index > 0),
                )
            }
        }
        forgetBaseRomButton.isEnabled = BaseRomCache.isPresent(this, selectedPatchGame())
        renderingPlayer = false
    }

    private fun optionGroupView(
        player: PlayerFormData,
        group: FormOptionGroup,
        collapsed: Boolean,
    ): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        fun populateBody() {
            if (body.childCount > 0) return
            group.options.forEach { option ->
                val optionControl = optionView(player, option)
                body.addView(optionControl, CompanionUi.insetTop(optionControl, this, 6))
            }
        }
        if (!collapsed) populateBody()
        val toggle = Button(this).apply {
            text = if (collapsed) "Show ${group.name}" else "Hide ${group.name}"
            CompanionUi.styleQuiet(this)
            setOnClickListener {
                preserveScrollPosition {
                    val show = body.visibility != View.VISIBLE
                    if (show) populateBody()
                    body.visibility = if (show) View.VISIBLE else View.GONE
                    text = if (show) "Hide ${group.name}" else "Show ${group.name}"
                }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toggle, CompanionUi.insetTop(toggle, this@GeneratorActivity, 6))
            addView(body, matchWrapParams())
        }
    }

    private fun optionView(player: PlayerFormData, option: FormOption): View {
        val value = player.values.opt(option.key).takeUnless { it == null || it == JSONObject.NULL }
            ?: option.defaultValue
        if (value is JSONObject || option.kind == "dict" || option.kind == "custom") {
            return structuredOptionView(player, option, value)
        }
        if (option.kind == "toggle" && value !is Boolean) {
            return textOptionView(player, option, value)
        }
        return when (option.kind) {
            "toggle" -> toggleOptionView(player, option, value)
            "choice" -> choiceOptionView(player, option, value)
            "text_choice" -> textChoiceOptionView(player, option, value)
            "range" -> textOptionView(player, option, value, numeric = option.specialValues.isEmpty())
            "list", "set" -> listOptionView(player, option, value)
            else -> textOptionView(player, option, value)
        }
    }

    private fun optionShell(option: FormOption, includeTitle: Boolean = true): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (includeTitle) addView(TextView(this@GeneratorActivity).apply {
                text = option.label
                setTypeface(typeface, Typeface.BOLD)
                CompanionUi.styleBody(this)
            }, matchWrapParams())
            if (option.description.isNotBlank()) addView(TextView(this@GeneratorActivity).apply {
                text = option.description
                CompanionUi.styleMuted(this)
            }, matchWrapParams())
        }

    private fun toggleOptionView(player: PlayerFormData, option: FormOption, value: Any?): View =
        optionShell(option, includeTitle = false).apply {
            addView(Switch(this@GeneratorActivity).apply {
                text = option.label
                isChecked = when (value) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    else -> value.toString().equals("true", true) || value.toString() == "1"
                }
                setOnCheckedChangeListener { _, checked ->
                    if (!renderingPlayer) updateOption(player, option.key, checked)
                }
            }, 0, matchWrapParams())
        }

    private fun choiceOptionView(player: PlayerFormData, option: FormOption, value: Any?): View {
        if (option.choices.isEmpty()) return textOptionView(player, option, value)
        val shell = optionShell(option)
        val current = value?.toString().orEmpty()
        if (option.choices.size <= 6) {
            shell.addView(RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                option.choices.forEach { choice ->
                    addView(RadioButton(this@GeneratorActivity).apply {
                        id = View.generateViewId()
                        text = choice.label
                        tag = choice.value
                        isChecked = choice.value == current
                    })
                }
                setOnCheckedChangeListener { group, checkedId ->
                    if (!renderingPlayer) {
                        group.findViewById<RadioButton>(checkedId)?.tag?.toString()?.let {
                            updateOption(player, option.key, it)
                        }
                    }
                }
            }, matchWrapParams())
        } else {
            shell.addView(Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@GeneratorActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    option.choices.map { it.label },
                )
                setSelection(option.choices.indexOfFirst { it.value == current }.coerceAtLeast(0), false)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (!renderingPlayer) option.choices.getOrNull(position)?.let {
                            if (player.values.opt(option.key)?.toString() != it.value) {
                                updateOption(player, option.key, it.value)
                            }
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }, matchWrapParams())
        }
        return shell
    }

    private fun textChoiceOptionView(player: PlayerFormData, option: FormOption, value: Any?): View {
        val shell = optionShell(option)
        val customLabel = "Custom value…"
        val choices = option.choices
        val current = value?.toString().orEmpty()
        val selectedChoice = choices.indexOfFirst { it.value == current }
        val customEditor = EditText(this).apply {
            hint = "Custom value"
            setSingleLine(true)
            setText(if (selectedChoice < 0) current else "")
            visibility = if (selectedChoice < 0) View.VISIBLE else View.GONE
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@GeneratorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                choices.map { it.label } + customLabel,
            )
            setSelection(if (selectedChoice >= 0) selectedChoice else choices.size, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val custom = position == choices.size
                    customEditor.visibility = if (custom) View.VISIBLE else View.GONE
                    if (!renderingPlayer && !custom) choices.getOrNull(position)?.let {
                        if (player.values.opt(option.key)?.toString() != it.value) {
                            updateOption(player, option.key, it.value)
                        }
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        customEditor.addTextChangedListener(valueWatcher {
            if (customEditor.visibility == View.VISIBLE) updateOption(player, option.key, it)
        })
        shell.addView(spinner, matchWrapParams())
        shell.addView(customEditor, matchWrapParams())
        return shell
    }

    private fun textOptionView(
        player: PlayerFormData,
        option: FormOption,
        value: Any?,
        numeric: Boolean = false,
    ): View = optionShell(option).apply {
        addView(EditText(this@GeneratorActivity).apply {
            if (numeric) {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                hint = listOfNotNull(option.minimum, option.maximum).joinToString(" – ")
            } else {
                setSingleLine(true)
                if (option.specialValues.isNotEmpty()) {
                    hint = option.specialValues.joinToString { "${it.label} (${it.value})" }
                }
            }
            setText(value?.toString().orEmpty())
            addTextChangedListener(valueWatcher { text ->
                val updated: Any = if (numeric) text.toIntOrNull() ?: text else text
                updateOption(player, option.key, updated)
            })
        }, matchWrapParams())
    }

    private fun listOptionView(player: PlayerFormData, option: FormOption, value: Any?): View =
        optionShell(option).apply {
            addView(EditText(this@GeneratorActivity).apply {
                hint = "One value per line"
                minLines = 2
                val items = value as? JSONArray
                setText(items?.let { array -> List(array.length()) { array.opt(it).toString() }.joinToString("\n") }
                    ?: value?.toString().orEmpty())
                addTextChangedListener(valueWatcher { text ->
                    val values = text.split(Regex("[,\\n]")).map { it.trim() }.filter { it.isNotEmpty() }
                    updateOption(player, option.key, JSONArray(values))
                })
            }, matchWrapParams())
        }

    private fun structuredOptionView(player: PlayerFormData, option: FormOption, value: Any?): View =
        optionShell(option).apply {
            addView(EditText(this@GeneratorActivity).apply {
                hint = "JSON value"
                minLines = 3
                typeface = Typeface.MONOSPACE
                setText(when (value) {
                    is JSONObject -> value.toString(2)
                    is JSONArray -> value.toString(2)
                    else -> value?.toString().orEmpty()
                })
                addTextChangedListener(valueWatcher { text ->
                    val parsed = runCatching { JSONTokener(text).nextValue() }.getOrElse { text }
                    updateOption(player, option.key, parsed)
                })
            }, matchWrapParams())
        }

    private fun valueWatcher(onChanged: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
            if (!renderingPlayer) onChanged(text?.toString().orEmpty())
        }
        override fun afterTextChanged(text: Editable?) = Unit
    }

    private fun updateOption(player: PlayerFormData, key: String, value: Any?) {
        player.values.put(key, value ?: JSONObject.NULL)
        markFormChanged()
    }

    private fun refreshAdvancedYaml(onReady: (() -> Unit)? = null) {
        if (playerForms.isEmpty()) return
        val snapshot = OfflineGenerator.encodePlayerForms(playerForms)
        status.text = "Preparing player YAML…"
        thread(name = "offline-player-yaml") {
            runCatching { OfflineGenerator.yamlFromPlayerForms(this, snapshot) }
                .onSuccess { yaml -> runOnUiThread {
                    renderingAdvancedYaml = true
                    yamlEditor.setText(yaml)
                    yamlEditor.setSelection(0)
                    renderingAdvancedYaml = false
                    advancedYamlDirty = false
                    status.text = "Player YAML is up to date."
                    onReady?.invoke()
                } }
                .onFailure { showError("Could not create player YAML", it) }
        }
    }

    private fun applyAdvancedYaml() {
        val yaml = yamlEditor.text.toString()
        if (yaml.isBlank()) {
            status.text = "Advanced YAML is empty."
            return
        }
        generateButton.isEnabled = false
        status.text = "Applying YAML to the player form…"
        loadFormsFromYaml(yaml) { forms, schemas ->
            applyPlayerForms(forms, schemas, 0)
            renderingAdvancedYaml = true
            yamlEditor.setText(yaml)
            renderingAdvancedYaml = false
            advancedYamlDirty = false
            status.text = "Advanced YAML applied to ${forms.size} player${if (forms.size == 1) "" else "s"}."
        }
    }

    private fun loadFormsFromYaml(
        yaml: String,
        onLoaded: (List<PlayerFormData>, Map<String, GameOptionSchema>) -> Unit,
    ) {
        thread(name = "offline-player-yaml-import") {
            runCatching { parseFormsAndSchemas(yaml) }
                .onSuccess { (forms, schemas) -> runOnUiThread { onLoaded(forms, schemas) } }
                .onFailure {
                    runOnUiThread { generateButton.isEnabled = playerForms.isNotEmpty() }
                    showError("Could not apply player YAML", it)
                }
        }
    }

    private fun rememberCurrentYaml() {
        if (playerForms.isEmpty()) {
            status.text = "Add at least one player before saving a YAML configuration."
            return
        }
        if (advancedYamlDirty) {
            promptForSavedYamlName(yamlEditor.text.toString())
            return
        }
        val snapshot = OfflineGenerator.encodePlayerForms(playerForms)
        status.text = "Preparing player YAML to remember…"
        thread(name = "offline-player-yaml-remember") {
            runCatching { OfflineGenerator.yamlFromPlayerForms(this, snapshot) }
                .onSuccess { yaml -> runOnUiThread { promptForSavedYamlName(yaml) } }
                .onFailure { showError("Could not prepare player YAML", it) }
        }
    }

    private fun promptForSavedYamlName(yaml: String) {
        if (yaml.isBlank()) {
            status.text = "The player YAML is empty."
            return
        }
        val suggestedName = savedYamlPlayers(playerForms).joinToString(" + ") { player ->
            "${player.name} · ${player.game}"
        }.take(100).ifBlank { "Player settings" }
        val nameEditor = EditText(this).apply {
            hint = "Configuration name"
            setSingleLine(true)
            setText(suggestedName)
            selectAll()
        }
        status.text = "YAML ready · choose a name to remember it."
        AlertDialog.Builder(this)
            .setTitle("Remember this YAML")
            .setMessage("The YAML will be kept privately by the companion until you delete it or clear app data.")
            .setView(nameEditor)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remember") { _, _ -> rememberYaml(nameEditor.text.toString(), yaml) }
            .show()
    }

    private fun rememberYaml(name: String, yaml: String) {
        status.text = "Validating and saving YAML…"
        thread(name = "offline-player-yaml-store") {
            runCatching {
                val (forms, _) = parseFormsAndSchemas(yaml)
                SavedYamlStore.save(this, name, yaml, savedYamlPlayers(forms))
            }.onSuccess { entry -> runOnUiThread {
                renderSavedYamls()
                status.text = "Remembered ${entry.name}."
            } }.onFailure { showError("Could not remember player YAML", it) }
        }
    }

    private fun openYamlPicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml", "text/plain"),
                )
            },
            REQUEST_IMPORT_YAML,
        )
    }

    private fun importYaml(uri: Uri) {
        historySettingsLoaded = true
        generateButton.isEnabled = false
        status.text = "Reading and validating player YAML…"
        thread(name = "offline-player-yaml-file-import") {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use {
                    it.readAtMost(SavedYamlStore.MAX_YAML_BYTES + 1)
                } ?: error("Could not open the selected YAML file.")
                require(bytes.isNotEmpty()) { "The selected YAML file is empty." }
                require(bytes.size <= SavedYamlStore.MAX_YAML_BYTES) {
                    "The selected YAML is larger than ${SavedYamlStore.MAX_YAML_BYTES / 1024} KiB."
                }
                val yaml = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                require('\u0000' !in yaml) { "The selected file is not a text YAML file." }
                val (forms, schemas) = parseFormsAndSchemas(yaml)
                val entry = SavedYamlStore.save(this, yamlDisplayName(uri), yaml, savedYamlPlayers(forms))
                ImportedYamlState(entry, yaml, forms, schemas)
            }.onSuccess { imported -> runOnUiThread {
                applyLoadedYaml(imported.yaml, imported.forms, imported.schemas)
                renderSavedYamls()
                status.text = "Imported, remembered, and loaded ${imported.entry.name}."
            } }.onFailure { error ->
                runOnUiThread { generateButton.isEnabled = playerForms.isNotEmpty() }
                showError("Could not import player YAML", error)
            }
        }
    }

    private fun loadSavedYaml(entry: SavedYamlEntry) {
        historySettingsLoaded = true
        generateButton.isEnabled = false
        status.text = "Loading ${entry.name}…"
        thread(name = "offline-player-yaml-library-load") {
            runCatching {
                val yaml = SavedYamlStore.read(this, entry.id)
                val (forms, schemas) = parseFormsAndSchemas(yaml)
                Triple(yaml, forms, schemas)
            }.onSuccess { (yaml, forms, schemas) -> runOnUiThread {
                applyLoadedYaml(yaml, forms, schemas)
                status.text = "Loaded ${entry.name} · ${forms.size} player${if (forms.size == 1) "" else "s"}."
            } }.onFailure { error ->
                runOnUiThread { generateButton.isEnabled = playerForms.isNotEmpty() }
                showError("Could not load ${entry.name}", error)
            }
        }
    }

    private fun parseFormsAndSchemas(
        yaml: String,
    ): Pair<List<PlayerFormData>, Map<String, GameOptionSchema>> {
        val forms = OfflineGenerator.playerFormsFromYaml(this, yaml)
        require(forms.isNotEmpty()) { "Add at least one player document." }
        val schemas = forms.map { it.game }.distinct().associateWith {
            OfflineGenerator.optionSchema(this, it)
        }
        return forms to schemas
    }

    private fun applyLoadedYaml(
        yaml: String,
        forms: List<PlayerFormData>,
        schemas: Map<String, GameOptionSchema>,
    ) {
        applyPlayerForms(forms, schemas, 0)
        historySettingsLoaded = true
        seedEditor.setText("")
        renderingAdvancedYaml = true
        yamlEditor.setText(yaml)
        yamlEditor.setSelection(0)
        renderingAdvancedYaml = false
        advancedYamlDirty = false
    }

    private fun savedYamlPlayers(forms: List<PlayerFormData>): List<SavedYamlPlayer> =
        forms.mapIndexed { index, player ->
            SavedYamlPlayer(
                name = player.name.ifBlank { "Player ${index + 1}" },
                game = player.game,
            )
        }

    private fun backfillSavedYamlMetadata() {
        if (!generatorReady || savedYamlMetadataRefreshInProgress) return
        val missingEntries = SavedYamlStore.list(this).filter { it.players.isEmpty() }
        if (missingEntries.isEmpty()) return
        savedYamlMetadataRefreshInProgress = true
        thread(name = "offline-player-yaml-metadata-backfill") {
            var changed = false
            missingEntries.forEach { entry ->
                runCatching {
                    val yaml = SavedYamlStore.read(this, entry.id)
                    val forms = OfflineGenerator.playerFormsFromYaml(this, yaml)
                    require(forms.isNotEmpty()) { "The saved YAML has no players." }
                    SavedYamlStore.updatePlayers(this, entry.id, savedYamlPlayers(forms))
                }.onSuccess { updated -> changed = changed || updated }
            }
            runOnUiThread {
                savedYamlMetadataRefreshInProgress = false
                if (changed) renderSavedYamls()
            }
        }
    }

    private fun renderSavedYamls() = preserveScrollPosition {
        savedYamlsContainer.removeAllViews()
        val entries = SavedYamlStore.list(this)
        savedYamlEntryCount = entries.size
        updateSavedYamlsToggleLabel()
        if (entries.isEmpty()) {
            savedYamlsContainer.addView(TextView(this).apply {
                text = "No YAML configurations remembered yet."
                CompanionUi.styleMuted(this)
                setPadding(0, CompanionUi.dp(this@GeneratorActivity, 8), 0, 0)
            }, matchWrapParams())
            return@preserveScrollPosition
        }
        entries.forEachIndexed { index, entry ->
            val panel = CompanionUi.panel(this).apply {
                addView(TextView(this@GeneratorActivity).apply {
                    text = entry.name
                    textSize = 17f
                    setTextColor(CompanionUi.text)
                    setTypeface(typeface, Typeface.BOLD)
                }, matchWrapParams())
                addView(TextView(this@GeneratorActivity).apply {
                    val saved = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(entry.createdAt))
                    val size = (entry.byteCount + 1023) / 1024
                    text = "Saved $saved · ${size.coerceAtLeast(1)} KiB"
                    CompanionUi.styleMuted(this)
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 2))
                addView(TextView(this@GeneratorActivity).apply {
                    text = if (entry.players.isEmpty()) {
                        "Player and game details will be added after this YAML is validated."
                    } else {
                        entry.players.joinToString("\n") { player -> "${player.name} · ${player.game}" }
                    }
                    CompanionUi.styleBody(this)
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 6))
                addView(Button(this@GeneratorActivity).apply {
                    text = "Load into generator"
                    CompanionUi.stylePrimary(this)
                    isEnabled = generatorReady
                    setOnClickListener { loadSavedYaml(entry) }
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 8))
                addView(Button(this@GeneratorActivity).apply {
                    text = "Save a copy to device"
                    CompanionUi.styleQuiet(this)
                    setOnClickListener {
                        runCatching { SavedYamlStore.read(this@GeneratorActivity, entry.id) }
                            .onSuccess { yaml -> beginExport(yamlExportName(entry.name), yaml.toByteArray()) }
                            .onFailure { showError("Could not read ${entry.name}", it) }
                    }
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 4))
                addView(Button(this@GeneratorActivity).apply {
                    text = "Delete saved YAML"
                    CompanionUi.styleDanger(this)
                    setOnClickListener { confirmDeleteSavedYaml(entry) }
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 4))
            }
            savedYamlsContainer.addView(
                panel,
                CompanionUi.insetTop(panel, this, if (index == 0) 8 else 10),
            )
        }
    }

    private fun updateSavedYamlsToggleLabel() {
        val noun = if (savedYamlEntryCount == 1) "saved YAML" else "saved YAMLs"
        savedYamlsToggleButton.text = if (savedYamlsContainer.visibility == View.VISIBLE) {
            "Hide $savedYamlEntryCount $noun"
        } else {
            "Show $savedYamlEntryCount $noun"
        }
    }

    private fun confirmDeleteSavedYaml(entry: SavedYamlEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete saved YAML?")
            .setMessage("Delete ${entry.name} from the companion? Exported copies on your device are not affected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                runCatching { SavedYamlStore.delete(this, entry.id) }
                    .onSuccess { deleted ->
                        if (deleted) {
                            renderSavedYamls()
                            status.text = "Deleted ${entry.name}."
                        } else {
                            status.text = "Could not delete ${entry.name}."
                        }
                    }
                    .onFailure { showError("Could not delete ${entry.name}", it) }
            }
            .show()
    }

    private fun yamlDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) {
                cursor.getString(column)
                    ?.filterNot { it.isISOControl() }
                    ?.trim()
                    ?.take(100)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
        }
        return "Imported YAML"
    }

    private fun yamlExportName(name: String): String {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Players" }
        return if (safeName.endsWith(".yaml", true) || safeName.endsWith(".yml", true)) safeName else "$safeName.yaml"
    }

    private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (output.size() < maxBytes) {
            val read = read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun exportPlayerYaml() {
        val name = if (playerForms.size > 1) "Players.yaml" else "Player.yaml"
        if (advancedYamlDirty) {
            beginExport(name, yamlEditor.text.toString().toByteArray())
            return
        }
        val snapshot = OfflineGenerator.encodePlayerForms(playerForms)
        status.text = "Preparing $name…"
        thread(name = "offline-player-yaml-export") {
            runCatching { OfflineGenerator.yamlFromPlayerForms(this, snapshot) }
                .onSuccess { yaml -> runOnUiThread { beginExport(name, yaml.toByteArray()) } }
                .onFailure { showError("Could not save player YAML", it) }
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

    private fun gameFromYaml(yaml: String): String? = playerGamesFromYaml(yaml)
        .firstOrNull()
        ?.takeIf { it in OfflineGenerator.availableGames(this) }

    private fun playerSlotFromPatchName(name: String, fallbackIndex: Int): Int =
        Regex("(?:^|_)P(\\d+)(?:_|\\.)", RegexOption.IGNORE_CASE)
            .find(name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: (fallbackIndex + 1)

    private fun selectedPatchGame(): String = patchFile?.let(::gameFromPatchFile) ?: currentTemplateGame

    private fun canPatchSelectedRom(): Boolean {
        if (patchFile == null) return false
        val game = selectedPatchGame()
        return OfflineGenerator.cachedCatalog().any { it.game == game && it.romPatch }
    }

    private fun gameFromPatchFile(patch: File): String? = runCatching {
        ZipFile(patch).use { archive ->
            val manifest = archive.getEntry("archipelago.json") ?: return@use null
            archive.getInputStream(manifest).bufferedReader(Charsets.UTF_8).use { reader ->
                JSONObject(reader.readText()).optString("game").takeIf { it.isNotBlank() }
            }
        }
    }.getOrNull()

    private fun generateSeed() {
        if (playerForms.isEmpty()) {
            status.text = "Add at least one player before generating."
            return
        }
        if (playerForms.any { it.name.isBlank() }) {
            status.text = "Every player needs a name before generating."
            return
        }
        if (advancedYamlDirty) {
            status.text = "Apply or discard the advanced YAML edits before generating."
            return
        }
        val playersJson = OfflineGenerator.encodePlayerForms(playerForms)
        generateButton.isEnabled = false
        exportSeedButton.isEnabled = false
        patchButton.isEnabled = false
        status.text = "Generating ${playerForms.size}-player seed… this can take a minute."
        val seed = seedEditor.text.toString()
        thread(name = "offline-seed-generation") {
            runCatching {
                val yaml = OfflineGenerator.yamlFromPlayerForms(this, playersJson)
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
            status.text = "Loading settings for seed ${entry.seed}…"
            loadFormsFromYaml(entry.yaml) { forms, schemas ->
                applyPlayerForms(forms, schemas, 0, clearGeneratedSeed = false)
                historySettingsLoaded = true
                seedEditor.setText(entry.seed)
                renderingAdvancedYaml = true
                yamlEditor.setText(entry.yaml)
                yamlEditor.setSelection(0)
                renderingAdvancedYaml = false
                advancedYamlDirty = false
                status.text = "Loaded seed ${entry.seed} · ${entry.players.joinToString()}"
            }
        }
    }

    private fun renderPatchChoices() = preserveScrollPosition { renderPatchChoicesContent() }

    private fun renderPatchChoicesContent() {
        patchesContainer.removeAllViews()
        if (availablePatches.isEmpty()) return
        patchesContainer.addView(TextView(this).apply {
            val heading = if (availablePatches.size == 1) {
                "ROM patch: ${availablePatches.first().name}"
            } else {
                "Choose which player's ROM to create:"
            }
            text = if (canPatchSelectedRom()) heading else "$heading\nThis world's output is export-only in the companion."
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

    private fun renderHistory() = preserveScrollPosition { renderHistoryContent() }

    private fun renderHistoryContent() {
        historyContainer.removeAllViews()
        val entries = SeedHistoryStore.list(this)
        historyEntryCount = entries.size
        updateHistoryToggleLabel()
        if (entries.isEmpty()) {
            historyContainer.addView(CompanionUi.panel(this).apply {
                addView(TextView(this@GeneratorActivity).apply {
                    text = "No saved seeds yet"
                    setTypeface(typeface, Typeface.BOLD)
                    CompanionUi.styleBody(this)
                }, matchWrapParams())
                addView(TextView(this@GeneratorActivity).apply {
                    text = "Generated seeds will appear here with their player files and hosting actions."
                    CompanionUi.styleMuted(this)
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 4))
            }, CompanionUi.insetTop(historyContainer, this, 8))
            return
        }
        entries.forEach { entry ->
            val zipArtifact = entry.files.firstOrNull {
                it.name.endsWith(".zip", ignoreCase = true) && File(it.path).isFile
            }
            val zipAvailable = zipArtifact != null
            val availablePatches = entry.patches.count { File(it.path).isFile }
            val selected = entry.id == currentHistoryId
            val playerGames = playerGamesFromYaml(entry.yaml)
            val patchGames = entry.patches.mapIndexed { index, patch ->
                val slot = playerSlotFromPatchName(patch.name, index)
                val playerName = entry.players.getOrNull(slot - 1) ?: "Player $slot"
                val game = playerGames.getOrNull(slot - 1)
                    ?: File(patch.path).takeIf { it.isFile }?.let(::gameFromPatchFile)
                    ?: "Unknown game"
                playerName to game
            }
            val entryPanel = CompanionUi.panel(this, selected).apply {
                addView(TextView(this@GeneratorActivity).apply {
                    text = if (selected) "Current seed · ${entry.seed}" else "Seed ${entry.seed}"
                    textSize = 18f
                    setTextColor(CompanionUi.text)
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, matchWrapParams())
                addView(TextView(this@GeneratorActivity).apply {
                    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(entry.createdAt))
                    text = date
                    CompanionUi.styleMuted(this)
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 2))
                addView(TextView(this@GeneratorActivity).apply {
                    text = entry.players.joinToString(" · ")
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    CompanionUi.styleBody(this)
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 10))
                if (patchGames.isNotEmpty()) addView(TextView(this@GeneratorActivity).apply {
                    text = if (patchGames.size == 1) {
                        "Patch game · ${patchGames.single().second}"
                    } else {
                        buildString {
                            append("Patch games")
                            patchGames.forEach { (playerName, game) -> append("\n$playerName · $game") }
                        }
                    }
                    CompanionUi.styleMuted(this)
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 4))
                addView(TextView(this@GeneratorActivity).apply {
                    val playerCount = entry.players.size
                    val patchSummary = if (entry.patches.isEmpty()) {
                        "no player patch · hosting only"
                    } else {
                        "$availablePatches/${entry.patches.size} patches"
                    }
                    text = "$playerCount player${if (playerCount == 1) "" else "s"} · $patchSummary · " +
                        if (zipAvailable) "ZIP ready" else "ZIP missing"
                    setTextColor(if (zipAvailable) CompanionUi.textMuted else CompanionUi.danger)
                    textSize = 13f
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 4))
                addView(Button(this@GeneratorActivity).apply {
                    text = if (selected) "Reload settings" else "Open settings"
                    CompanionUi.styleSecondary(this)
                    setOnClickListener { openHistoryEntry(entry, loadSettings = true) }
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 10))
                addView(LinearLayout(this@GeneratorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(this@GeneratorActivity).apply {
                        text = "Save ZIP"
                        isEnabled = zipAvailable
                        CompanionUi.styleQuiet(this)
                        setOnClickListener {
                            zipArtifact?.let { File(it.path) }?.takeIf { it.isFile }
                                ?.let { beginExport(it.name, it.readBytes()) }
                        }
                    }, CompanionUi.weightedButtonParams(this@GeneratorActivity, 4))
                    addView(Button(this@GeneratorActivity).apply {
                        text = "Host online"
                        isEnabled = zipAvailable && !hostingInProgress
                        CompanionUi.styleQuiet(this)
                        setOnClickListener {
                            zipArtifact?.let { File(it.path) }?.takeIf { it.isFile }
                                ?.let { hostSeed(it, entry.id) }
                        }
                    }, CompanionUi.weightedButtonParams(this@GeneratorActivity))
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 4))
                addView(Button(this@GeneratorActivity).apply {
                    text = "Delete seed"
                    CompanionUi.styleDanger(this)
                    setOnClickListener { confirmDelete(entry) }
                }, CompanionUi.insetTop(this, this@GeneratorActivity, 4))
            }
            historyContainer.addView(
                entryPanel,
                CompanionUi.insetTop(entryPanel, this, if (entry === entries.first()) 8 else 10),
            )
        }
    }

    private fun updateHistoryToggleLabel() {
        val count = historyEntryCount
        val noun = if (count == 1) "saved seed" else "saved seeds"
        historyToggleButton.text = if (historyContainer.visibility == View.VISIBLE) {
            "Hide $count $noun"
        } else {
            "Show $count $noun"
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
                        status.text = if (result.room.lastPort > 0) {
                            "Room created · connect to archipelago.gg:${result.room.lastPort}. Open Hosted rooms to manage it."
                        } else {
                            "Room created on archipelago.gg. Open Hosted rooms to refresh its port and manage it."
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

    private fun chooseBaseRom(requirements: RomRequirements, input: RomInputRequirement) {
        AlertDialog.Builder(this)
            .setTitle("Clean ROM required for ${requirements.game}")
            .setMessage(
                "Requested by the installed game world:\n${input.description}\n\n" +
                    "Select a clean original ROM, not an already patched or randomized ROM. " +
                    "The game world will validate it, and the app will cache it privately for later seeds.",
            )
            .setNegativeButton("Cancel") { _, _ ->
                pendingRomRequirements = null
                pendingRomInputs.clear()
                pendingRomInputUris.clear()
                patchButton.isEnabled = canPatchSelectedRom()
            }
            .setPositiveButton("Choose clean ROM") { _, _ -> openBaseRomPicker(input) }
            .show()
    }

    private fun openBaseRomPicker(input: RomInputRequirement) {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Android file providers commonly label GameCube ISOs as
            // application/x-iso9660-image instead of application/octet-stream.
            // A wildcard is required for consistent SAF interoperability; the
            // official patcher still validates GZLE01 before accepting the file.
            type = if (pendingRomRequirements?.streaming == true) "*/*" else "application/octet-stream"
        }
        val label = input.description.ifBlank { input.fileName.ifBlank { "clean ROM" } }
        startActivityForResult(
            Intent.createChooser(picker, "Choose $label"),
            REQUEST_BASE_ROM,
        )
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
            .setNegativeButton("Cancel") { _, _ ->
                pendingRomRequirements = null
                pendingRomInputs.clear()
                patchButton.isEnabled = canPatchSelectedRom()
            }
            .setPositiveButton("Choose another ROM") { _, _ -> openBaseRomPicker(input) }
            .show()
    }

    private fun patchWithCachedBaseRomOrChoose() {
        val selectedPatch = patchFile ?: return
        val game = selectedPatchGame()
        patchButton.isEnabled = false
        status.text = "Checking for cached ROM inputs for $game…"
        thread(name = "offline-rom-cache-check") {
            runCatching { OfflineGenerator.romRequirements(this, selectedPatch.readBytes()) }
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
                    continueRomSelection(selectedPatch)
                }
                .onFailure { error ->
                    runOnUiThread { patchButton.isEnabled = true }
                    showError("Could not determine the required ROMs", error)
                }
        }
    }

    private fun continueRomSelection(selectedPatch: File) {
        val requirements = pendingRomRequirements ?: return
        val missing = requirements.inputs.firstOrNull {
            if (requirements.streaming) it.key !in pendingRomInputUris else it.key !in pendingRomInputs
        }
        if (missing == null) {
            runOnUiThread {
                forgetBaseRomButton.isEnabled = true
                status.text = "Validating ROM inputs and patching ${selectedPatch.name}…"
            }
            if (requirements.streaming) {
                pendingStreamingPatch = selectedPatch to pendingRomInputUris.toMap()
                val extension = requirements.resultExtension.ifBlank { ".iso" }
                runOnUiThread {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_TITLE, "${selectedPatch.nameWithoutExtension}$extension")
                        },
                        REQUEST_STREAMING_ROM_OUTPUT,
                    )
                }
            } else {
                patchRomInputs(selectedPatch, pendingRomInputs.toMap())
            }
            return
        }
        runOnUiThread {
            patchButton.isEnabled = true
            forgetBaseRomButton.isEnabled = BaseRomCache.isPresent(this, requirements.game)
            status.text = "Select ${missing.description}."
            chooseBaseRom(requirements, missing)
        }
    }

    private fun acceptBaseRom(uri: Uri) {
        val selectedPatch = patchFile ?: return
        val requirements = pendingRomRequirements ?: return
        val input = requirements.inputs.firstOrNull {
            if (requirements.streaming) it.key !in pendingRomInputUris else it.key !in pendingRomInputs
        } ?: return
        patchButton.isEnabled = false
        status.text = "Reading ${input.description}…"
        thread(name = "offline-rom-input") {
            runCatching {
                if (requirements.streaming) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    OfflineGenerator.validateRomInputDocument(this, selectedPatch.readBytes(), input.key, uri)
                    pendingRomInputUris[input.key] = uri
                } else {
                    val selectedBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read the selected ROM")
                    OfflineGenerator.validateRomInput(this, selectedPatch.readBytes(), input.key, selectedBytes)
                    pendingRomInputs[input.key] = selectedBytes
                }
            }.onSuccess {
                continueRomSelection(selectedPatch)
            }.onFailure { error ->
                runOnUiThread {
                    patchButton.isEnabled = true
                    status.text = "The selected file was rejected. Choose the correct clean ROM."
                    showIncorrectBaseRom(requirements, input, error)
                }
            }
        }
    }

    private fun patchRomInputs(selectedPatch: File, romInputs: Map<String, ByteArray>) {
        val requirements = pendingRomRequirements ?: return
        thread(name = "offline-rom-patching") {
            runCatching {
                createPatchedRom(selectedPatch, romInputs).also {
                    romInputs.forEach { (key, bytes) ->
                        BaseRomCache.storeAfterSuccessfulPatch(this, bytes, requirements.game, key)
                    }
                }
            }
                .onSuccess { output -> runOnUiThread {
                    pendingRomRequirements = null
                    pendingRomInputs.clear()
                    patchButton.isEnabled = true
                    forgetBaseRomButton.isEnabled = true
                    status.text = "ROM created and clean ROM inputs cached. Choose where to save it."
                    beginExport(output.name, output.readBytes())
                } }
                .onFailure {
                    BaseRomCache.forget(this, requirements.game)
                    pendingRomRequirements = null
                    pendingRomInputs.clear()
                    runOnUiThread { patchButton.isEnabled = true }
                    showError("ROM patching failed", it)
                }
        }
    }

    private fun patchRomDocuments(destination: Uri) {
        val (selectedPatch, inputs) = pendingStreamingPatch ?: return
        val requirements = pendingRomRequirements ?: return
        status.text = "Patching ${requirements.game} directly into the selected ISO…"
        thread(name = "offline-disc-patching") {
            runCatching {
                OfflineGenerator.patchRomDocuments(this, selectedPatch.readBytes(), inputs, destination)
                inputs.forEach { (key, uri) -> BaseRomDocumentStore.store(this, requirements.game, key, uri) }
            }.onSuccess { runOnUiThread {
                pendingStreamingPatch = null
                pendingRomRequirements = null
                pendingRomInputUris.clear()
                patchButton.isEnabled = true
                forgetBaseRomButton.isEnabled = true
                status.text = "Saved patched ${requirements.game} ISO."
            } }.onFailure { error ->
                BaseRomCache.forget(this, requirements.game)
                pendingStreamingPatch = null
                pendingRomRequirements = null
                pendingRomInputUris.clear()
                runOnUiThread { patchButton.isEnabled = true }
                showError("Disc patching failed", error)
            }
        }
    }

    private fun createPatchedRom(selectedPatch: File, romInputs: Map<String, ByteArray>): File {
        val patchBytes = selectedPatch.readBytes()
        val extension = OfflineGenerator.patchResultExtension(this, patchBytes)
        val output = File(filesDir, "offline_generator/output/${selectedPatch.nameWithoutExtension}$extension")
        return OfflineGenerator.patchRom(this, patchBytes, romInputs, output)
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
            if (requestCode == REQUEST_BASE_ROM) {
                pendingRomRequirements = null
                pendingRomInputs.clear()
                pendingRomInputUris.clear()
                patchButton.isEnabled = canPatchSelectedRom()
            }
            if (requestCode == REQUEST_STREAMING_ROM_OUTPUT) {
                pendingStreamingPatch = null
                pendingRomRequirements = null
                pendingRomInputUris.clear()
                patchButton.isEnabled = canPatchSelectedRom()
            }
            return
        }
        when (requestCode) {
            REQUEST_BASE_ROM -> acceptBaseRom(data.data!!)
            REQUEST_STREAMING_ROM_OUTPUT -> patchRomDocuments(data.data!!)
            REQUEST_IMPORT_YAML -> importYaml(data.data!!)
            REQUEST_EXPORT -> {
                val export = pendingExport ?: return
                val destination = data.data!!
                runCatching {
                    contentResolver.openOutputStream(destination)?.use { it.write(export.second) }
                        ?: error("Could not open the selected destination")
                }.onSuccess {
                    status.text = "Saved ${export.first}"
                    if (export.first.endsWith(".gba", ignoreCase = true) ||
                        export.first.endsWith(".gbc", ignoreCase = true) ||
                        export.first.endsWith(".gb", ignoreCase = true) ||
                        export.first.endsWith(".sfc", ignoreCase = true) ||
                        export.first.endsWith(".smc", ignoreCase = true)
                    ) {
                        offerRetroArchLaunch(export.first, destination)
                    }
                }.onFailure { showError("Could not save ${export.first}", it) }
                pendingExport = null
            }
        }
    }

    private fun offerRetroArchLaunch(name: String, uri: Uri) {
        val snes = name.endsWith(".sfc", ignoreCase = true) || name.endsWith(".smc", ignoreCase = true)
        val coreDescription = if (snes) {
            "the custom SNES9x Archipelago core."
        } else {
            "the custom mGBA Archipelago core."
        }
        AlertDialog.Builder(this)
            .setTitle("ROM ready")
            .setMessage("Saved $name. Launch it now in RetroArch with $coreDescription")
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

    /** Keep layout changes from letting ScrollView chase a focused button or a newly resized child. */
    private fun preserveScrollPosition(update: () -> Unit) {
        if (!::screenScrollView.isInitialized) {
            update()
            return
        }
        val previousY = screenScrollView.scrollY
        update()
        screenScrollView.post {
            val contentHeight = screenScrollView.getChildAt(0)?.height ?: 0
            val maximumY = (contentHeight - screenScrollView.height).coerceAtLeast(0)
            screenScrollView.scrollTo(0, previousY.coerceAtMost(maximumY))
        }
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
        private const val REQUEST_STREAMING_ROM_OUTPUT = 204
        private const val REQUEST_IMPORT_YAML = 203
    }
}
