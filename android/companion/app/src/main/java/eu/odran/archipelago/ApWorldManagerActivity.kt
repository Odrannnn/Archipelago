package eu.odran.archipelago

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/** Installs trusted APWorld Python packages for on-device generation and generic GBA patching. */
class ApWorldManagerActivity : Activity() {
    private lateinit var worldsContainer: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { textSize = 16f }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@ApWorldManagerActivity).apply {
                text = "Installed APWorlds"
                textSize = 24f
            })
            addView(TextView(this@ApWorldManagerActivity).apply {
                text = "Imported worlds can add games, complete YAML templates, mixed-seed generation, and " +
                    "standard GBA patching. An APWorld is executable Python code and cannot be sandboxed here: " +
                    "install files only from authors you trust. Live emulator synchronization still needs a " +
                    "game-specific bridge adapter; currently Metroid Fusion and The Minish Cap have one."
            })
            addView(Button(this@ApWorldManagerActivity).apply {
                text = "Import trusted .apworld"
                setOnClickListener { confirmImport() }
            }, matchWrap())
            addView(worldsContainer, matchWrap())
            addView(status, matchWrap())
        }
        val scroll = ScrollView(this).apply { addView(content) }
        SystemBarInsets.apply(window, scroll)
        setContentView(scroll)
        renderWorlds()
    }

    private fun confirmImport() {
        AlertDialog.Builder(this)
            .setTitle("Trust this APWorld?")
            .setMessage(
                "APWorlds contain executable Python, not just settings. The app checks archive paths, sizes, " +
                    "manifest structure, and Archipelago version compatibility, but it cannot prove the code is safe."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose file") { _, _ ->
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                    },
                    REQUEST_APWORLD,
                )
            }
            .show()
    }

    @Deprecated("Uses the platform file picker result API available to android.app.Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_APWORLD || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "Validating and installing APWorld…"
        thread(name = "apworld-install") {
            runCatching {
                val installed = ImportedApWorldStore.install(this, uri)
                val catalog = OfflineGenerator.refreshCatalog(this)
                installed to catalog
            }.onSuccess { (installed, catalog) ->
                runOnUiThread {
                    status.text = if (catalog.any { it.game == installed.game }) {
                        "Installed and loaded ${installed.game} ${installed.worldVersion}."
                    } else {
                        val failure = OfflineGenerator.cachedWorldFailures()[installed.packageName]
                        "Installed ${installed.game}, but it did not load: ${shortFailure(failure)}"
                    }
                    renderWorlds()
                }
            }.onFailure { error -> runOnUiThread { status.text = "Could not install APWorld:\n${error.message ?: error.javaClass.simpleName}" } }
        }
    }

    private fun renderWorlds() {
        worldsContainer.removeAllViews()
        val installed = ImportedApWorldStore.list(this)
        if (installed.isEmpty()) {
            worldsContainer.addView(TextView(this).apply { text = "No additional APWorlds installed." })
            return
        }
        val capabilities = OfflineGenerator.cachedCatalog().associateBy { it.game }
        val failures = OfflineGenerator.cachedWorldFailures()
        installed.forEach { world ->
            val capability = capabilities[world.game]
            val failure = failures[world.packageName]
            worldsContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 24, 0, 24)
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = "${world.game} ${world.worldVersion}"
                    textSize = 19f
                })
                addView(TextView(this@ApWorldManagerActivity).apply {
                    val installedDate = DateFormat.getDateTimeInstance().format(Date(world.installedAt))
                    val capabilityText = when {
                        failure != null -> "Load failed: ${shortFailure(failure)}"
                        capability == null -> "Not loaded yet; leave and reopen this screen after the generator starts"
                        capability.romPatch -> "Generation + template + generic GBA patching"
                        else -> "Generation + template; ROM format is not supported by the Android patcher"
                    }
                    text = "$capabilityText\nPackage ${world.packageName} · installed $installedDate\n" +
                        "SHA-256 ${world.sha256.take(16)}…"
                })
                if (failure != null) {
                    addView(Button(this@ApWorldManagerActivity).apply {
                        text = "Show full load error"
                        setOnClickListener {
                            AlertDialog.Builder(this@ApWorldManagerActivity)
                                .setTitle("${world.game} load error")
                                .setMessage(failure)
                                .setPositiveButton("Close", null)
                                .show()
                        }
                    })
                }
                addView(Button(this@ApWorldManagerActivity).apply {
                    text = "Remove ${world.game}"
                    setOnClickListener { confirmRemove(world) }
                })
            })
        }
    }

    private fun confirmRemove(world: ImportedApWorld) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${world.game}?")
            .setMessage("Its extracted APWorld files and registry entry will be deleted. Existing generated seeds are preserved.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                if (ImportedApWorldStore.remove(this, world.packageName)) {
                    status.text = "Removed ${world.game}. If it was already loaded, fully restart the companion before importing another version."
                    renderWorlds()
                } else {
                    status.text = "Could not remove ${world.game}."
                }
            }
            .show()
    }

    private fun matchWrap() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun shortFailure(failure: String?): String = failure
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.lastOrNull()
        ?.take(240)
        ?: "no world class was registered"

    companion object {
        private const val REQUEST_APWORLD = 301
    }
}
