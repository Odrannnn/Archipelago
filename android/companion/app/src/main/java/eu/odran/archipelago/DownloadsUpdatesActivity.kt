package eu.odran.archipelago

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Downloads signed companion components and installs cores through RetroArch's SAF provider. */
@SuppressLint("SetTextI18n")
class DownloadsUpdatesActivity : Activity() {
    private data class ComponentViews(val status: TextView, val action: Button)

    private lateinit var catalogStatus: TextView
    private lateinit var operationStatus: TextView
    private lateinit var coreFolderStatus: TextView
    private lateinit var checkButton: Button
    private lateinit var chooseCoreFolderButton: Button
    private val componentViews = linkedMapOf<ManagedComponent, ComponentViews>()
    private var assets = emptyMap<ManagedComponent, ComponentAsset>()
    private var apkStates = emptyMap<ManagedComponent, InstalledApkState?>()
    private var coreStates = emptyMap<ManagedComponent, InstalledCoreState>()
    private var busyComponent: ManagedComponent? = null
    private var pendingApkAsset: ComponentAsset? = null
    private var pendingApkFile: File? = null
    private var stateRefreshGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalogStatus = TextView(this).apply { CompanionUi.styleMuted(this) }
        operationStatus = TextView(this).apply {
            CompanionUi.styleMuted(this)
            setPadding(0, CompanionUi.dp(this@DownloadsUpdatesActivity, 8), 0, 0)
        }
        coreFolderStatus = TextView(this).apply { CompanionUi.styleMuted(this) }
        checkButton = Button(this).apply {
            text = "Check now"
            CompanionUi.styleSecondary(this)
            setOnClickListener { refreshCatalog(force = true) }
        }
        chooseCoreFolderButton = Button(this).apply {
            text = "Choose RetroArch cores folder"
            CompanionUi.styleSecondary(this)
            setOnClickListener { chooseCoreFolder() }
        }

        val content = CompanionUi.screen(this).apply {
            addView(
                CompanionUi.pageTitle(
                    this@DownloadsUpdatesActivity,
                    "Downloads and updates",
                    "Install the companion's emulator, tracker, and custom core components.",
                ),
                CompanionUi.fullWidth(),
            )
            addView(CompanionUi.card(
                this@DownloadsUpdatesActivity,
                "Release check",
                "Release metadata is cached for 24 hours. Downloads are accepted only after their GitHub SHA-256 digest matches.",
            ).apply {
                addView(catalogStatus, CompanionUi.fullWidth())
                addView(checkButton, CompanionUi.insetTop(checkButton, this@DownloadsUpdatesActivity, 8))
                addView(operationStatus, CompanionUi.fullWidth())
            }, CompanionUi.cardParams(this@DownloadsUpdatesActivity))
            addView(CompanionUi.card(
                this@DownloadsUpdatesActivity,
                "RetroArch core access",
                "Choose RetroArch's cores folder once. Android remembers this permission and lets the companion verify and replace only the custom cores through RetroArch's storage provider.",
            ).apply {
                addView(coreFolderStatus, CompanionUi.fullWidth())
                addView(
                    chooseCoreFolderButton,
                    CompanionUi.insetTop(chooseCoreFolderButton, this@DownloadsUpdatesActivity, 8),
                )
            }, CompanionUi.cardParams(this@DownloadsUpdatesActivity))

            ManagedComponent.entries.forEach { component ->
                val componentStatus = TextView(this@DownloadsUpdatesActivity).apply {
                    text = "Waiting for release information…"
                    CompanionUi.styleBody(this)
                }
                val action = Button(this@DownloadsUpdatesActivity).apply {
                    text = "Unavailable"
                    isEnabled = false
                    CompanionUi.stylePrimary(this)
                    setOnClickListener { componentAction(component) }
                }
                componentViews[component] = ComponentViews(componentStatus, action)
                addView(CompanionUi.card(
                    this@DownloadsUpdatesActivity,
                    component.displayName,
                    componentDescription(component),
                ).apply {
                    addView(componentStatus, CompanionUi.fullWidth())
                    addView(action, CompanionUi.insetTop(action, this@DownloadsUpdatesActivity, 8))
                }, CompanionUi.cardParams(this@DownloadsUpdatesActivity))
            }
        }
        val scroll = ScrollView(this).apply { addView(content) }
        SystemBarInsets.apply(window, scroll)
        setContentView(scroll)
        renderCoreFolder()
        refreshCatalog(force = false)
    }

    override fun onResume() {
        super.onResume()
        val pendingAsset = pendingApkAsset
        val pendingFile = pendingApkFile
        if (pendingAsset != null && pendingFile?.isFile == true && ApkComponentInstaller.canRequestInstalls(this)) {
            pendingApkAsset = null
            pendingApkFile = null
            operationStatus.text = "Opening Android's installer for ${pendingAsset.component.displayName}…"
            ApkComponentInstaller.launchInstaller(this, pendingFile)
        }
        refreshInstalledStates()
    }

    @Deprecated("Uses the platform SAF result API available to android.app.Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CORE_TREE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { RetroArchCoreStore.storeSelection(this, uri, data.flags) }
            .onSuccess {
                operationStatus.text = "RetroArch core access saved."
                renderCoreFolder()
                refreshInstalledStates()
            }
            .onFailure { error ->
                operationStatus.text = "Could not use that folder: ${error.message ?: error.javaClass.simpleName}"
            }
    }

    private fun refreshCatalog(force: Boolean) {
        if (busyComponent != null) return
        setBusy(null, true)
        catalogStatus.text = if (force) "Refreshing GitHub releases…" else "Loading release information…"
        thread(name = "component-release-check") {
            runCatching { ComponentReleaseClient(this).load(force) }
                .onSuccess { result -> runOnUiThread {
                    assets = result.assets.associateBy { it.component }
                    val checked = DateFormat.getDateTimeInstance().format(Date(result.checkedAt))
                    catalogStatus.text = buildString {
                        append(if (result.cached) "Last checked " else "Checked ")
                        append(checked)
                        result.warning?.let { append("\n$it") }
                    }
                    setBusy(null, false)
                    refreshInstalledStates()
                } }
                .onFailure { error -> runOnUiThread {
                    catalogStatus.text = "Could not check releases: ${error.message ?: error.javaClass.simpleName}"
                    setBusy(null, false)
                    renderComponents()
                } }
        }
    }

    private fun refreshInstalledStates() {
        if (assets.isEmpty()) {
            renderComponents()
            return
        }
        val generation = ++stateRefreshGeneration
        val currentAssets = assets
        thread(name = "component-installed-check") {
            val apk = ManagedComponent.entries
                .filter { it.kind == ComponentKind.APK }
                .associateWith { ApkComponentInstaller.installedState(this, it) }
            val cores = currentAssets.values
                .filter { it.component.kind == ComponentKind.CORE }
                .associate { it.component to runCatching { RetroArchCoreStore.installedState(this, it) }
                    .getOrDefault(InstalledCoreState()) }
            runOnUiThread {
                if (generation != stateRefreshGeneration) return@runOnUiThread
                apkStates = apk
                coreStates = cores
                renderCoreFolder()
                renderComponents()
            }
        }
    }

    private fun componentAction(component: ManagedComponent) {
        val asset = assets[component] ?: return
        if (component.kind == ComponentKind.CORE && RetroArchCoreStore.selectedTree(this) == null) {
            chooseCoreFolder()
            return
        }
        if (component.kind == ComponentKind.CORE) {
            AlertDialog.Builder(this)
                .setTitle("Install ${component.displayName}?")
                .setMessage(
                    "Close RetroArch before continuing. The companion will verify the download, preserve the " +
                        "current core temporarily, and restore it automatically if replacement fails.",
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download and install") { _, _ -> downloadAndInstall(asset) }
                .show()
        } else {
            downloadAndInstall(asset)
        }
    }

    private fun downloadAndInstall(asset: ComponentAsset) {
        if (busyComponent != null) return
        setBusy(asset.component, true)
        operationStatus.text = "Preparing ${asset.component.displayName} ${asset.version}…"
        thread(name = "component-download-${asset.component.componentId}") {
            runCatching {
                var lastPercent = -1L
                val file = ComponentDownloadStore.download(this, asset) { downloaded, total ->
                    val percent = if (total > 0) downloaded * 100 / total else 0
                    if (percent != lastPercent) {
                        lastPercent = percent
                        runOnUiThread {
                            operationStatus.text = "Downloading ${asset.component.displayName}… $percent% · " +
                                "${formatBytes(downloaded)} / ${formatBytes(total)}"
                        }
                    }
                }
                when (asset.component.kind) {
                    ComponentKind.APK -> ApkComponentInstaller.verify(this, asset, file)
                    ComponentKind.CORE -> RetroArchCoreStore.install(this, asset, file)
                }
                file
            }.onSuccess { file -> runOnUiThread {
                setBusy(asset.component, false)
                if (asset.component.kind == ComponentKind.APK) {
                    if (ApkComponentInstaller.canRequestInstalls(this)) {
                        operationStatus.text = "Opening Android's installer for ${asset.component.displayName}…"
                        ApkComponentInstaller.launchInstaller(this, file)
                    } else {
                        pendingApkAsset = asset
                        pendingApkFile = file
                        operationStatus.text =
                            "Allow Archipelago Companion to install apps, then return to continue."
                        ApkComponentInstaller.requestInstallPermission(this)
                    }
                } else {
                    operationStatus.text = "Installed ${asset.component.displayName} ${asset.version}."
                    refreshInstalledStates()
                }
            } }.onFailure { error -> runOnUiThread {
                setBusy(asset.component, false)
                operationStatus.text =
                    "Could not install ${asset.component.displayName}: ${error.message ?: error.javaClass.simpleName}"
                refreshInstalledStates()
            } }
        }
    }

    private fun renderComponents() {
        componentViews.forEach { (component, views) ->
            val asset = assets[component]
            val busy = busyComponent != null
            if (asset == null) {
                views.status.text = "No compatible release asset was found."
                views.action.text = "Unavailable"
                views.action.isEnabled = false
                return@forEach
            }
            if (component.kind == ComponentKind.APK) {
                val installed = apkStates[component]
                views.status.text = when {
                    installed == null -> "Not installed · available ${asset.version} · ${formatBytes(asset.byteCount)}"
                    installed.alternateBuild ->
                        "Installed debug build ${installed.versionName}. Release ${asset.version} will install alongside it."
                    installed.versionName == asset.version ->
                        "Installed ${installed.versionName} · up to date · ${formatBytes(asset.byteCount)}"
                    ComponentVersion.isNewer(asset.version, installed.versionName) ->
                        "Installed ${installed.versionName} · update ${asset.version} available · ${formatBytes(asset.byteCount)}"
                    else -> "Installed ${installed.versionName} · newer than published ${asset.version}"
                }
                views.action.text = when {
                    installed == null || installed.alternateBuild -> "Download and install"
                    installed.versionName == asset.version -> "Reinstall"
                    ComponentVersion.isNewer(asset.version, installed.versionName) -> "Download update"
                    else -> "Install published build"
                }
                views.action.isEnabled = !busy
            } else {
                val hasTree = RetroArchCoreStore.selectedTree(this) != null
                val installed = coreStates[component] ?: InstalledCoreState()
                val relation = installed.relationTo(asset.version)
                views.status.text = if (!hasTree) {
                    "RetroArch cores folder permission required · available ${asset.version}"
                } else when (relation) {
                    CoreReleaseRelation.VERIFIED_CURRENT ->
                        "Installed ${installed.installedFileName} · verified and up to date"
                    CoreReleaseRelation.CURRENT_VERSION_DIFFERENT_BUILD ->
                        "Installed ${installed.installedVersion} · latest version label, but this file differs " +
                            "from the published build"
                    CoreReleaseRelation.UPDATE_AVAILABLE ->
                        "Installed ${installed.installedVersion ?: installed.installedFileName} · " +
                            "${asset.version} update available"
                    CoreReleaseRelation.NEWER_THAN_RELEASE ->
                        "Installed ${installed.installedVersion ?: installed.installedFileName} · " +
                            "newer than published ${asset.version}"
                    CoreReleaseRelation.NOT_INSTALLED ->
                        "Not installed · available ${asset.version} · ${formatBytes(asset.byteCount)}"
                }
                views.action.text = if (!hasTree) {
                    "Choose cores folder"
                } else when (relation) {
                    CoreReleaseRelation.VERIFIED_CURRENT -> "Reinstall"
                    CoreReleaseRelation.CURRENT_VERSION_DIFFERENT_BUILD -> "Replace with published build"
                    CoreReleaseRelation.NEWER_THAN_RELEASE -> "Install published build"
                    CoreReleaseRelation.NOT_INSTALLED,
                    CoreReleaseRelation.UPDATE_AVAILABLE -> "Download and install"
                }
                views.action.isEnabled = !busy
            }
        }
    }

    private fun renderCoreFolder() {
        val selected = RetroArchCoreStore.selectedTree(this)
        coreFolderStatus.text = if (selected == null) {
            "No RetroArch cores folder selected."
        } else {
            "Access granted to RetroArch / cores."
        }
        chooseCoreFolderButton.text = if (selected == null) {
            "Choose RetroArch cores folder"
        } else {
            "Change cores folder"
        }
    }

    private fun setBusy(component: ManagedComponent?, busy: Boolean) {
        busyComponent = if (busy) component ?: BUSY_CATALOG else null
        checkButton.isEnabled = !busy
        chooseCoreFolderButton.isEnabled = !busy
        renderComponents()
    }

    private fun chooseCoreFolder() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            },
            REQUEST_CORE_TREE,
        )
    }

    private fun componentDescription(component: ManagedComponent): String = when (component) {
        ManagedComponent.DOLPHIN ->
            "Custom Dolphin build with the loopback memory socket required for GameCube live sync."
        ManagedComponent.POPTRACKER ->
            "Android PopTracker port used by the active room's tracker shortcut."
        ManagedComponent.MGBA_CORE ->
            "Custom GBA, GB, and GBC core with atomic upstream BizHawk-client operations."
        ManagedComponent.SNES9X_CORE ->
            "Optional mapper-independent SNI fallback for supported SNES clients."
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val REQUEST_CORE_TREE = 701
        // A sentinel keeps the existing single-busy-operation state without a separate flag.
        private val BUSY_CATALOG = ManagedComponent.DOLPHIN
    }
}
