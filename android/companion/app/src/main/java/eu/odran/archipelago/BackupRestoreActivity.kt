package eu.odran.archipelago

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** SAF-based export and replacement restore for all portable app-owned data. */
class BackupRestoreActivity : CompanionActivity() {
    private lateinit var inventory: TextView
    private lateinit var status: TextView
    private lateinit var exportButton: Button
    private lateinit var restoreButton: Button
    private val createBackup = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::exportBackup)
    }
    private val openBackup = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::confirmRestore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inventory = TextView(this).apply {
            text = "Calculating app-owned data…"
            CompanionUi.styleBody(this)
        }
        status = TextView(this).apply {
            CompanionUi.styleMuted(this)
            setPadding(0, CompanionUi.dp(this@BackupRestoreActivity, 8), 0, 0)
        }
        exportButton = Button(this).apply {
            text = "Create backup"
            CompanionUi.stylePrimary(this)
            setOnClickListener { chooseBackupDestination() }
        }
        restoreButton = Button(this).apply {
            text = "Restore from backup"
            CompanionUi.styleDanger(this)
            setOnClickListener { chooseBackupFile() }
        }

        val content = CompanionUi.screen(this).apply {
            addView(
                CompanionUi.pageTitle(
                    this@BackupRestoreActivity,
                    "Backup and restore",
                    "Move your Archipelago Companion library to another install or keep a recovery copy.",
                ),
                CompanionUi.fullWidth(),
            )
            addView(CompanionUi.card(
                this@BackupRestoreActivity,
                "Included data",
                "Cached base ROMs, patched ROMs, generated seeds, imported APWorlds, saved YAMLs, " +
                    "joined and hosted rooms, generator drafts, and companion settings.",
            ).apply {
                addView(inventory, CompanionUi.fullWidth())
                addView(exportButton, CompanionUi.insetTop(exportButton, this@BackupRestoreActivity, 10))
            }, CompanionUi.cardParams(this@BackupRestoreActivity))
            addView(CompanionUi.card(
                this@BackupRestoreActivity,
                "Keep the archive private",
                "The backup is not encrypted. It contains your cached ROM files, room passwords, and the " +
                    "website session used to manage hosted rooms. Only restore an archive you trust because " +
                    "imported APWorlds contain executable Python.",
            ), CompanionUi.cardParams(this@BackupRestoreActivity))
            addView(CompanionUi.card(
                this@BackupRestoreActivity,
                "Restore a backup",
                "Restore validates every file before replacing current app data. File-manager ROM and ISO " +
                    "permission grants cannot move between Android installs, so external files may need to be selected again.",
            ).apply {
                addView(restoreButton, CompanionUi.fullWidth())
                addView(status, CompanionUi.fullWidth())
            }, CompanionUi.cardParams(this@BackupRestoreActivity))
        }
        val scroll = CompanionUi.scrollView(this, content)
        SystemBarInsets.apply(window, scroll)
        setContentView(scroll)
        refreshInventory()
    }

    private fun refreshInventory() {
        thread(name = "backup-inventory") {
            runCatching { CompanionBackup.inventory(this) }
                .onSuccess { summary -> runOnUiThread { inventory.text = summary.inventoryText() } }
                .onFailure { error -> runOnUiThread {
                    inventory.text = "Could not inspect app storage: ${error.message ?: error.javaClass.simpleName}"
                } }
        }
    }

    private fun chooseBackupDestination() {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        createBackup.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "Archipelago-Companion-$date.apbackup")
        })
    }

    private fun chooseBackupFile() {
        openBackup.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        })
    }

    private fun exportBackup(uri: Uri) {
        setBusy(true, "Creating and verifying backup archive…")
        thread(name = "companion-backup-export") {
            runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { CompanionBackup.export(this, it) }
                    ?: error("Could not open the selected backup destination.")
            }.onSuccess { summary -> runOnUiThread {
                setBusy(false, "Backup created · ${summary.fileCount} files · ${formatBytes(summary.byteCount)}")
            } }.onFailure { error -> runOnUiThread {
                setBusy(false, "Could not create backup: ${error.message ?: error.javaClass.simpleName}")
            } }
        }
    }

    private fun confirmRestore(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Replace current app data?")
            .setMessage(
                "The selected backup will replace cached ROMs, seeds, APWorlds, YAMLs, rooms, and settings " +
                    "after it passes all integrity checks. The emulator bridge will stop and the companion " +
                    "will close when restore finishes."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ -> restoreBackup(uri) }
            .show()
    }

    private fun restoreBackup(uri: Uri) {
        setBusy(true, "Validating backup before changing app data…")
        val bridgeWasStopped = AtomicBoolean(false)
        thread(name = "companion-backup-restore") {
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    CompanionBackup.restore(this, input) {
                        bridgeWasStopped.set(stopService(Intent(this, BridgeService::class.java)))
                    }
                }
                    ?: error("Could not open the selected backup.")
            }.onSuccess { summary -> runOnUiThread {
                setBusy(false, "Restore complete · ${summary.fileCount} files · ${formatBytes(summary.byteCount)}")
                AlertDialog.Builder(this)
                    .setTitle("Restore complete")
                    .setMessage(
                        "The backup was restored successfully. Archipelago Companion will now close so " +
                            "restored APWorlds and settings load cleanly. Open it again when you are ready."
                    )
                    .setCancelable(false)
                    .setPositiveButton("Close companion") { _, _ ->
                        finishAffinity()
                        Process.killProcess(Process.myPid())
                    }
                    .show()
            } }.onFailure { error -> runOnUiThread {
                if (error !is CompanionBackupRollbackException && bridgeWasStopped.get()) {
                    startForegroundService(Intent(this, BridgeService::class.java))
                }
                val prefix = if (error is CompanionBackupRollbackException) {
                    "Restore needs attention"
                } else {
                    "Restore failed; current data was kept"
                }
                setBusy(false, "$prefix: ${error.message ?: error.javaClass.simpleName}")
            } }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        exportButton.isEnabled = !busy
        restoreButton.isEnabled = !busy
        status.text = message
    }

    private fun CompanionBackupSummary.inventoryText(): String = buildString {
        append("${fileCount} files · ${formatBytes(byteCount)}")
        categories.filter { it.fileCount > 0 }.forEach { category ->
            append("\n• ${category.label}: ${category.fileCount} files · ${formatBytes(category.byteCount)}")
        }
        if (fileCount == 0) append("\nNo app-owned files have been created yet; settings will still be included.")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }

    companion object {
    }
}
