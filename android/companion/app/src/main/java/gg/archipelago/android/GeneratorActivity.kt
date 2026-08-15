package gg.archipelago.android

import android.app.Activity
import android.app.AlertDialog
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
import java.util.Date
import kotlin.concurrent.thread

/** Creates player YAMLs, generates seeds, and patches a user-supplied ROM entirely offline. */
class GeneratorActivity : Activity() {
    private lateinit var yamlEditor: EditText
    private lateinit var seedEditor: EditText
    private lateinit var playerCountView: TextView
    private lateinit var generateButton: Button
    private lateinit var exportSeedButton: Button
    private lateinit var patchButton: Button
    private lateinit var patchesContainer: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var status: TextView

    private var seedFile: File? = null
    private var patchFile: File? = null
    private var availablePatches: List<File> = emptyList()
    private var pendingExport: Pair<String, ByteArray>? = null
    private var historySettingsLoaded = false
    private var currentHistoryId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            addView(patchesContainer, matchWrapParams())
            addView(patchButton, matchWrapParams())
            addView(status, matchWrapParams())
            addView(TextView(this@GeneratorActivity).apply {
                text = "Generated seed history"
                textSize = 22f
                setPadding(0, 32, 0, 8)
            })
            addView(historyContainer, matchWrapParams())
        }
        setContentView(ScrollView(this).apply { addView(content) })
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
                    text = "Delete from history"
                    setOnClickListener { confirmDelete(entry) }
                }, matchWrapParams())
            }, matchWrapParams())
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
