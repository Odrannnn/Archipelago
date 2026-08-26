package eu.odran.archipelago

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Installs trusted APWorld Python packages for on-device generation and ROM patching. */
@SuppressLint("SetTextI18n")
class ApWorldManagerActivity : Activity() {
    private lateinit var worldsContainer: LinearLayout
    private lateinit var dependenciesContainer: LinearLayout
    private lateinit var status: TextView
    private lateinit var dependencyStatus: TextView
    private var dependencyCatalog = emptyList<NativeDependencyAsset>()
    private var declaredDependencies = emptyList<DeclaredPythonDependency>()
    private var dependencyBusy: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        worldsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dependenciesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { CompanionUi.styleBody(this) }
        dependencyStatus = TextView(this).apply { CompanionUi.styleMuted(this) }
        val content = CompanionUi.screen(this).apply {
            addView(CompanionUi.pageTitle(
                this@ApWorldManagerActivity,
                "Game worlds",
                "Use built-in Android games or add trusted community APWorlds.",
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
                "Android Python dependencies",
                "Trusted APWorlds may declare Python packages. Universal wheels install directly from PyPI; native Android builds use the verified build cache. Packages stay in this app's private storage.",
            ).apply {
                addView(dependencyStatus, CompanionUi.fullWidth())
                addView(Button(this@ApWorldManagerActivity).apply {
                    text = "Refresh dependency catalog"
                    CompanionUi.styleSecondary(this)
                    setOnClickListener { refreshDependencyCatalog(force = true) }
                }, CompanionUi.insetTop(this, this@ApWorldManagerActivity, 8))
                addView(dependenciesContainer, CompanionUi.insetTop(dependenciesContainer, this@ApWorldManagerActivity, 8))
            }, CompanionUi.cardParams(this@ApWorldManagerActivity))
            addView(CompanionUi.card(
                this@ApWorldManagerActivity,
                "Built-in SNES live sync",
                "Verified upstream SNI clients included with the companion. RetroArch nightly Network Commands are the default; the custom SNES9x Archipelago core is an optional mapper-independent fallback.",
            ).apply {
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = OfflineGenerator.bundledWorlds(this@ApWorldManagerActivity)
                        .filter { it.platform == "SNES" }
                        .joinToString("\n") { "• ${it.game}" }
                    CompanionUi.styleBody(this)
                }, CompanionUi.fullWidth())
            }, CompanionUi.cardParams(this@ApWorldManagerActivity))
            addView(CompanionUi.card(
                this@ApWorldManagerActivity,
                "Built-in N64 live sync",
                "Upstream N64 clients included with the companion. Standard BizHawk clients use the shared memory adapter; OoT runs its upstream Lua connector semantics directly. RetroArch nightly Network Commands and Mupen64Plus-Next provide ROM and RDRAM access.",
            ).apply {
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = OfflineGenerator.bundledWorlds(this@ApWorldManagerActivity)
                        .filter { it.platform == "N64" }
                        .joinToString("\n") { "• ${it.game}" }
                    CompanionUi.styleBody(this)
                }, CompanionUi.fullWidth())
            }, CompanionUi.cardParams(this@ApWorldManagerActivity))
            addView(CompanionUi.card(
                this@ApWorldManagerActivity,
                "Built-in GameCube live sync",
                "Upstream Dolphin Memory Engine clients included with the companion. Use the Dolphin Archipelago Android fork for direct memory access.",
            ).apply {
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = OfflineGenerator.bundledWorlds(this@ApWorldManagerActivity)
                        .filter { it.platform == "GameCube" }
                        .joinToString("\n") { "• ${it.game}" }
                    CompanionUi.styleBody(this)
                }, CompanionUi.fullWidth())
            }, CompanionUi.cardParams(this@ApWorldManagerActivity))
            addView(CompanionUi.card(
                this@ApWorldManagerActivity,
                "Available games",
                "Built-in games ship with the app. Imported worlds can be removed independently.",
            ).apply {
                addView(worldsContainer, matchWrap())
            }, CompanionUi.cardParams(this@ApWorldManagerActivity))
        }
        val scroll = CompanionUi.scrollView(this, content)
        SystemBarInsets.apply(window, scroll)
        setContentView(scroll)
        renderWorlds()
        renderDependencies()
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
        refreshDependencyCatalog(force = false)
    }

    private fun confirmImport() {
        AlertDialog.Builder(this)
            .setTitle("Trust this APWorld?")
            .setMessage(
                "APWorlds contain executable Python, not just settings. The app checks archive paths, sizes, " +
                    "manifest structure, and Archipelago version compatibility, but it cannot prove the code is safe. " +
                    "Installing also authorizes dependencies declared by this APWorld."
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
                val dependencies = provisionDependencies(listOf(installed))
                val catalog = OfflineGenerator.refreshCatalog(this)
                Triple(installed, catalog, dependencies)
            }.onSuccess { (installed, catalog, dependencies) ->
                runOnUiThread {
                    status.text = if (catalog.any { it.game == installed.game }) {
                        buildString {
                            append("Installed and loaded ${installed.game} ${installed.worldVersion}.")
                            if (dependencies.installed.isNotEmpty()) {
                                append("\nAutomatically installed ")
                                append(dependencies.installed.joinToString { "${it.packageName} ${it.version}" })
                                append('.')
                            }
                            if (dependencies.pureInstalled.isNotEmpty()) {
                                append("\nInstalled from PyPI: ")
                                append(dependencies.pureInstalled.joinToString { "${it.packageName} ${it.version}" })
                                append('.')
                            }
                            if (dependencies.unresolved.isNotEmpty()) {
                                append("\nStill unavailable: ")
                                append(dependencies.unresolved.joinToString { it.requirement })
                                append('.')
                            }
                        }
                    } else {
                        val failure = OfflineGenerator.cachedWorldFailures()[installed.packageName]
                        showLoadError(installed.game, failure ?: "No world class was registered.")
                        "Installed ${installed.game}, but it could not load: ${shortFailure(failure)}"
                    }
                    renderWorlds()
                    renderDependencies()
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
        OfflineGenerator.bundledWorlds(this).forEach { world ->
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
                        append("\nIncluded with the companion · ${world.platform}")
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

    private fun refreshDependencyCatalog(force: Boolean) {
        if (dependencyBusy != null) return
        dependencyStatus.text = if (force) {
            "Refreshing the verified Android build cache…"
        } else {
            "Checking APWorld declarations and the verified Android build cache…"
        }
        thread(name = "native-dependency-catalog") {
            runCatching {
                val result = NativeDependencyCatalogClient(this).load(force)
                val provisioned = NativeDependencyProvisioner.installFromCatalog(
                    this,
                    result,
                    ImportedApWorldStore.list(this),
                    onStarting = { asset -> runOnUiThread {
                        dependencyBusy = asset.packageName
                        dependencyStatus.text = "Automatically installing ${asset.packageName} ${asset.version}…"
                        renderDependencies()
                    } },
                    onProgress = { asset, downloaded, total -> runOnUiThread {
                        dependencyStatus.text =
                            "Downloading ${asset.packageName}: ${formatBytes(downloaded)} / ${formatBytes(total)}"
                    } },
                )
                if (provisioned.installed.isNotEmpty()) OfflineGenerator.refreshCatalog(this)
                provisioned
            }.onSuccess { provisioned -> runOnUiThread {
                    val result = provisioned.catalog
                    dependencyCatalog = result.assets
                    declaredDependencies = provisioned.declarations
                    dependencyBusy = null
                    dependencyStatus.text = buildString {
                        append("${result.assets.size} compatible package build")
                        if (result.assets.size != 1) append('s')
                        append(if (result.cached) " in the verified cache." else " verified from GitHub.")
                        if (provisioned.installed.isNotEmpty()) {
                            append("\nAutomatically installed ")
                            append(provisioned.installed.joinToString { "${it.packageName} ${it.version}" })
                            append(" from the verified Android build cache.")
                        }
                        if (provisioned.pureInstalled.isNotEmpty()) {
                            append("\nInstalled universal PyPI wheels: ")
                            append(provisioned.pureInstalled.joinToString { "${it.packageName} ${it.version}" })
                            append('.')
                        }
                        if (provisioned.unresolved.isNotEmpty()) {
                            append("\nNative build still required: ")
                            append(provisioned.unresolved.joinToString { it.requirement })
                            append('.')
                        }
                        result.warning?.let { append("\n$it") }
                    }
                    renderWorlds()
                    renderDependencies()
                } }
                .onFailure { error -> runOnUiThread {
                    dependencyBusy = null
                    dependencyStatus.text =
                        "Could not prepare reviewed Android dependencies: ${error.message ?: error.javaClass.simpleName}"
                    renderDependencies()
                } }
        }
    }

    private fun renderDependencies() {
        dependenciesContainer.removeAllViews()
        val imported = ImportedApWorldStore.list(this)
        val relevant = NativeDependencyProvisioner.requiredAssets(dependencyCatalog, declaredDependencies)
        val installed = NativeDependencyStore.list(this).associateBy { it.packageName }
        if (relevant.isEmpty() && installed.isEmpty()) {
            dependenciesContainer.addView(TextView(this).apply {
                text = if (imported.isEmpty()) {
                    "Import an APWorld to inspect and install its declared Python packages."
                } else {
                    "The currently imported worlds declare no additional cached native packages."
                }
                CompanionUi.styleBody(this)
            }, CompanionUi.fullWidth())
            return
        }

        relevant.forEach { asset ->
            val record = installed[asset.packageName]
            val exact = NativeDependencyStore.isInstalled(this, asset)
            val panel = CompanionUi.panel(this).apply {
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = "${asset.packageName} ${asset.version}"
                    textSize = 18f
                    setTextColor(CompanionUi.text)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = buildString {
                        append(if (exact) "Installed and verified" else if (record != null) {
                            "Version ${record.version} installed · reviewed update available"
                        } else {
                            "Available for Android ${asset.androidAbi}"
                        })
                        append("\nModule ${asset.moduleName} · Python ${asset.pythonAbi} · ${formatBytes(asset.byteCount)}")
                        append("\nRequested by ")
                        append(declaredDependencies.filter {
                            it.packageName.replace('_', '-').equals(asset.packageName.replace('_', '-'), ignoreCase = true)
                        }.joinToString { it.declaredBy })
                    }
                    CompanionUi.styleMuted(this)
                    setPadding(0, CompanionUi.dp(this@ApWorldManagerActivity, 4), 0, 0)
                })
                addView(Button(this@ApWorldManagerActivity).apply {
                    text = when {
                        dependencyBusy == asset.packageName -> "Installing…"
                        exact -> "Remove package"
                        record != null -> "Install reviewed update"
                        else -> "Download and install"
                    }
                    isEnabled = dependencyBusy == null
                    if (exact) CompanionUi.styleDanger(this) else CompanionUi.stylePrimary(this)
                    setOnClickListener {
                        if (exact) confirmRemoveDependency(asset) else confirmInstallDependency(asset)
                    }
                }, CompanionUi.insetTop(this, this@ApWorldManagerActivity, 8))
            }
            dependenciesContainer.addView(panel, CompanionUi.insetTop(panel, this, 8))
        }

        installed.values.filter { record -> relevant.none { it.packageName == record.packageName } }.forEach { record ->
            val requestedBy = declaredDependencies.filter {
                it.packageName.replace('_', '-').equals(record.packageName.replace('_', '-'), ignoreCase = true)
            }
            val panel = CompanionUi.panel(this).apply {
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = "${record.packageName} ${record.version}"
                    CompanionUi.styleBody(this)
                })
                addView(TextView(this@ApWorldManagerActivity).apply {
                    text = if (requestedBy.isEmpty()) {
                        "Installed package is not required by a currently imported world."
                    } else {
                        "Installed for ${requestedBy.joinToString { it.declaredBy }}."
                    }
                    CompanionUi.styleMuted(this)
                })
                addView(Button(this@ApWorldManagerActivity).apply {
                    text = "Remove package"
                    isEnabled = dependencyBusy == null
                    CompanionUi.styleDanger(this)
                    setOnClickListener { removeDependency(record.packageName) }
                }, CompanionUi.insetTop(this, this@ApWorldManagerActivity, 8))
            }
            dependenciesContainer.addView(panel, CompanionUi.insetTop(panel, this, 8))
        }
    }

    private fun provisionDependencies(worlds: List<ImportedApWorld>): NativeDependencyProvisionResult {
        try {
            return NativeDependencyProvisioner.installFor(
                this,
                worlds,
                onStarting = { asset -> runOnUiThread {
                    dependencyBusy = asset.packageName
                    dependencyStatus.text = "Automatically installing ${asset.packageName} ${asset.version}…"
                    renderDependencies()
                } },
                onProgress = { asset, downloaded, total -> runOnUiThread {
                    dependencyStatus.text =
                        "Downloading ${asset.packageName}: ${formatBytes(downloaded)} / ${formatBytes(total)}"
                } },
            ).also { provisioned ->
                dependencyCatalog = provisioned.catalog.assets
                declaredDependencies = provisioned.declarations
                provisioned.catalog.warning?.let { warning -> runOnUiThread {
                    dependencyStatus.text = warning
                } }
            }
        } finally {
            runOnUiThread { dependencyBusy = null }
        }
    }

    private fun confirmInstallDependency(asset: NativeDependencyAsset, skipConfirmation: Boolean = false) {
        val install = {
            dependencyBusy = asset.packageName
            dependencyStatus.text = "Downloading ${asset.packageName} ${asset.version}…"
            renderDependencies()
            thread(name = "native-dependency-install") {
                runCatching {
                    NativeDependencyStore.downloadAndInstall(this, asset) { downloaded, total ->
                        runOnUiThread {
                            dependencyStatus.text = "Downloading ${asset.packageName}: ${formatBytes(downloaded)} / ${formatBytes(total)}"
                        }
                    }
                }.onSuccess { runOnUiThread {
                    dependencyBusy = null
                    dependencyStatus.text = "Installed ${asset.packageName} ${asset.version}. Failed APWorld imports may require a full companion restart."
                    renderDependencies()
                    thread(name = "native-dependency-catalog-refresh") {
                        runCatching { OfflineGenerator.refreshCatalog(this) }
                            .onSuccess { runOnUiThread { renderWorlds() } }
                    }
                } }.onFailure { error -> runOnUiThread {
                    dependencyBusy = null
                    dependencyStatus.text = "Could not install ${asset.packageName}: ${error.message ?: error.javaClass.simpleName}"
                    renderDependencies()
                } }
            }
        }
        if (skipConfirmation) {
            install()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Install ${asset.packageName}?")
            .setMessage(
                "This reviewed build was produced from ${asset.sourceUrl} and is restricted to ${asset.androidAbi}. " +
                    "The download and its upstream source are both verified by SHA-256 before installation.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install") { _, _ -> install() }
            .show()
    }

    private fun confirmRemoveDependency(asset: NativeDependencyAsset) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${asset.packageName}?")
            .setMessage("Worlds which require this native module will lose that functionality until it is installed again.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> removeDependency(asset.packageName) }
            .show()
    }

    private fun removeDependency(packageName: String) {
        dependencyStatus.text = if (NativeDependencyStore.remove(this, packageName)) {
            "Removed $packageName. Restart the companion if the module was already loaded."
        } else {
            "Could not remove $packageName."
        }
        renderDependencies()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun capabilitySummary(capability: WorldCapability?, failure: String?): String {
        if (failure != null) return "Load failed: ${shortFailure(failure)}"
        if (capability == null) return "Loading capabilities…"
        val features = buildList {
            if (capability.generation) add("generation")
            if (capability.romPatch) add("ROM patching")
            if (capability.liveBridge) add("live emulator sync")
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
                    renderDependencies()
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
        ?.firstOrNull()
        ?.take(240)
        ?: "no world class was registered"

    companion object {
        private const val REQUEST_APWORLD = 301
    }
}
