package gg.archipelago.android

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** Creates player YAMLs, generates seeds, and patches a user-supplied ROM entirely offline. */
class GeneratorActivity : Activity() {
    private lateinit var yamlEditor: EditText
    private lateinit var seedEditor: EditText
    private lateinit var generateButton: Button
    private lateinit var exportSeedButton: Button
    private lateinit var patchButton: Button
    private lateinit var status: TextView

    private var seedFile: File? = null
    private var patchFile: File? = null
    private var pendingExport: Pair<String, ByteArray>? = null

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
        status = TextView(this).apply {
            text = "Starting Python 3.12…"
            textSize = 16f
        }

        val saveYamlButton = Button(this).apply {
            text = "Save player YAML"
            setOnClickListener {
                beginExport("Player.yaml", yamlEditor.text.toString().toByteArray())
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
                seedFile?.let { beginExport(it.name, it.readBytes()) }
            }
        }
        patchButton = Button(this).apply {
            text = "Create patched GBA"
            isEnabled = false
            setOnClickListener { chooseBaseRom() }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@GeneratorActivity).apply {
                text = "Offline Metroid Fusion Generator"
                textSize = 24f
            })
            addView(TextView(this@GeneratorActivity).apply {
                text = "Edit the player YAML below. Multiple YAML documents separated by --- create a multiworld. " +
                    "Generation and patching need no network connection. Your base ROM is only read after you select it."
            })
            addView(seedEditor, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(yamlEditor, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(saveYamlButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(generateButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(exportSeedButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(patchButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(ScrollView(this).apply { addView(content) })

        thread(name = "offline-generator-startup") {
            runCatching { OfflineGenerator.defaultYaml(this) }
                .onSuccess { template ->
                    runOnUiThread {
                        yamlEditor.setText(template)
                        yamlEditor.isEnabled = true
                        generateButton.isEnabled = true
                        status.text = "Ready · Metroid Fusion APWorld 1.22.4"
                    }
                }
                .onFailure { showError("Could not start the offline generator", it) }
        }
    }

    private fun generateSeed() {
        generateButton.isEnabled = false
        exportSeedButton.isEnabled = false
        patchButton.isEnabled = false
        status.text = "Generating… this can take a minute."
        val yaml = yamlEditor.text.toString()
        val seed = seedEditor.text.toString()
        thread(name = "offline-seed-generation") {
            runCatching { OfflineGenerator.generate(this, yaml, seed) }
                .onSuccess { result ->
                    seedFile = result.files.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }
                        ?.let { File(it.path) }
                    patchFile = result.patches.firstOrNull()?.let { File(it.path) }
                    runOnUiThread {
                        generateButton.isEnabled = true
                        exportSeedButton.isEnabled = seedFile?.isFile == true
                        patchButton.isEnabled = patchFile?.isFile == true
                        val names = result.files.joinToString("\n") { "• ${it.name}" }
                        status.text = "Generation complete · seed ${result.seed}\n$names"
                    }
                }
                .onFailure {
                    runOnUiThread { generateButton.isEnabled = true }
                    showError("Generation failed", it)
                }
        }
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
        status.text = "Patching ROM locally…"
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

    companion object {
        private const val REQUEST_BASE_ROM = 201
        private const val REQUEST_EXPORT = 202
    }
}
