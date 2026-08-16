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

/** Installs trusted APWorld Python packages for on-device generation and ROM patching. */
class ApWorldManagerActivity : Activity() {
    private lateinit var worldsContainer: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { CompanionUi.styleBody(this) }
        val content = CompanionUi.screen(this).apply {
            addView(CompanionUi.pageTitle(
                this@ApWorldManagerActivity,
                "Game worlds",
                "Use built-in mGBA games or add trusted community APWorlds.",
            ), CompanionUi.fullWidth())
            addView(CompanionUi.card(
                this@ApWorldManagerActivity,
                "Import an additional game",
                "Community APWorlds contain executable Python. Only install files from authors you trust.",
            ).apply {
                addView(Button(this@ApWorldManagerActivity).apply {
                    text = "Import trusted .apworld"
                    CompanionUi.stylePrimary(this)
                    setOnClickListener { confirmImport() }
                }, matchWrap())
                addView(status, CompanionUi.insetTop(status, this@ApWorldManagerActivity, 8))
            }, CompanionUi.cardParams(this@ApWorldManagerActivity))
            addView(CompanionUi.card(
                this@ApWorldManagerActivity,
                "Available games",
                "Built-in games ship with the app. Imported worlds can be removed independently.",
            ).apply {
                addView(worldsContainer, matchWrap())
            }, CompanionUi.cardParams(this@ApWorldManagerActivity))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        SystemBarInsets.apply(window, scroll)
        setContentView(scroll)
        renderWorlds()
        status.text = "Loading game capabilities…"
        thread(name = "apworld-catalog") {
            runCatching { OfflineGenerator.refreshCatalog(this) }
                .onSuccess { catalog -> runOnUiThread {
                    val bundled = catalog.count { it.source == "bundled" }
                    val imported = catalog.count { it.source == "imported" }
                    status.text = "$bundled built-in games ready · $imported imported"
                    renderWorlds()
                } }
                .onFailure { error -> runOnUiThread {
                    status.text = "Could not load game catalog: ${error.message ?: error.javaClass.simpleName}"
                    renderWorlds()
                } }
        }
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
        val capabilities = OfflineGenerator.cachedCatalog().associateBy { it.game }
        val failures = OfflineGenerator.cachedWorldFailures()

        worldsContainer.addView(TextView(this).apply {
            text = "Built in"
            textSize = 17f
            setTextColor(CompanionUi.text)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, CompanionUi.insetTop(worldsContainer, this, 4))
        OfflineGenerator.bundledWorlds().forEach { world ->
            val game = world.game
            val capability = capabilities[game]
            val failure = failures[world.packageName]
            val panel = CompanionUi.panel(this).apply {
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = game
                    textSize = 18f
                    setTextColor(CompanionUi.text)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = buildString {
                        append(capabilitySummary(capability, failure))
                        append("\nIncluded with the companion")
                        capability?.version?.takeIf { it != "0.0.0" }?.let { append(" · world $it") }
                    }
                    CompanionUi.styleMuted(this)
                    setPadding(0, CompanionUi.dp(this@ApWorldManagerActivity, 4), 0, 0)
                })
                if (failure != null) addView(Button(this@ApWorldManagerActivity).apply {
                    text = "Show load error"
                    CompanionUi.styleQuiet(this)
                    setOnClickListener { showLoadError(game, failure) }
                }, CompanionUi.insetTop(this, this@ApWorldManagerActivity, 6))
            }
            worldsContainer.addView(panel, CompanionUi.insetTop(panel, this, 8))
        }

        worldsContainer.addView(TextView(this).apply {
            text = "Imported"
            textSize = 17f
            setTextColor(CompanionUi.text)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, CompanionUi.insetTop(worldsContainer, this, 16))
        if (installed.isEmpty()) {
            val emptyPanel = CompanionUi.panel(this).apply {
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = "No additional APWorlds imported"
                    CompanionUi.styleBody(this)
                })
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = "Use the built-in games above or import a trusted community world."
                    CompanionUi.styleMuted(this)
                }, CompanionUi.insetTop(this, this@ApWorldManagerActivity, 4))
            }
            worldsContainer.addView(emptyPanel, CompanionUi.insetTop(emptyPanel, this, 8))
            return
        }
        installed.forEach { world ->
            val capability = capabilities[world.game]
            val failure = failures[world.packageName]
            val panel = CompanionUi.panel(this).apply {
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = "${world.game} ${world.worldVersion}"
                    textSize = 18f
                    setTextColor(CompanionUi.text)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@ApWorldManagerActivity).apply {
                    val installedDate = DateFormat.getDateTimeInstance().format(Date(world.installedAt))
                    text = "${capabilitySummary(capability, failure)}\n" +
                        "Package ${world.packageName} · installed $installedDate\n" +
                        "SHA-256 ${world.sha256.take(16)}…"
                    CompanionUi.styleMuted(this)
                    setPadding(0, CompanionUi.dp(this@ApWorldManagerActivity, 4), 0, CompanionUi.dp(this@ApWorldManagerActivity, 6))
                })
                if (failure != null) {
                    addView(Button(this@ApWorldManagerActivity).apply {
                        text = "Show full load error"
                        CompanionUi.styleQuiet(this)
                        setOnClickListener { showLoadError(world.game, failure) }
                    })
                }
                addView(Button(this@ApWorldManagerActivity).apply {
                    text = "Remove ${world.game}"
                    CompanionUi.styleDanger(this)
                    setOnClickListener { confirmRemove(world) }
                })
            }
            worldsContainer.addView(panel, CompanionUi.insetTop(panel, this, 8))
        }
    }

    private fun capabilitySummary(capability: WorldCapability?, failure: String?): String {
        if (failure != null) return "Load failed: ${shortFailure(failure)}"
        if (capability == null) return "Loading capabilities…"
        val features = buildList {
            if (capability.generation) add("generation")
            if (capability.romPatch) add("ROM patching")
            if (capability.liveBridge) add("live mGBA sync")
        }
        return if (features.isEmpty()) "Loaded" else features.joinToString(" + ").replaceFirstChar(Char::uppercase)
    }

    private fun showLoadError(game: String, failure: String) {
        AlertDialog.Builder(this)
            .setTitle("$game load error")
            .setMessage(failure)
            .setPositiveButton("Close", null)
            .show()
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
