package eu.odran.archipelago

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/** Launches a patched ROM directly with the custom mGBA Archipelago core. */
object RetroArchLauncher {
    private const val PACKAGE_NAME = "com.retroarch.aarch64"
    private const val ACTIVITY_NAME = "com.retroarch.browser.retroactivity.RetroActivityFuture"
    private const val CORE_FILE_NAME = "mgba_apbridge_v6_libretro_android.so"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun isRunningRom(gameName: String?, playerSlot: Int?, serverAddress: String?): Boolean {
        if (gameName.isNullOrBlank() || playerSlot == null || serverAddress.isNullOrBlank()) return false
        return BridgeService.activeGameName == gameName &&
            BridgeService.activePlayerSlot == playerSlot &&
            normalizedAddress(BridgeService.activeServerAddress) == normalizedAddress(serverAddress)
    }

    /** Returns true when an existing matching RetroArch activity was resumed. */
    fun launch(
        context: Context,
        savedRom: Uri,
        gameName: String? = null,
        playerSlot: Int? = null,
        serverAddress: String? = null,
    ): Boolean {
        val app = context.packageManager.getApplicationInfo(PACKAGE_NAME, 0)
        val storage = Environment.getExternalStorageDirectory()
        val externalFiles = File(storage, "Android/data/$PACKAGE_NAME/files")
        val configPath = File(externalFiles, "retroarch.cfg").absolutePath
        val romReference = localPath(savedRom) ?: savedRom.toString()
        val corePath = File(app.dataDir, "cores/$CORE_FILE_NAME").absolutePath
        val resumeExisting = isRunningRom(gameName, playerSlot, serverAddress)

        val intent = Intent().apply {
            component = ComponentName(PACKAGE_NAME, ACTIVITY_NAME)
            if (resumeExisting) {
                // Preserve the active native core and video surface when this exact room is running.
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            } else {
                // A genuinely new launch gets a clean task instead of a stale suspended surface.
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            }
            putExtra("ROM", romReference)
            putExtra("LIBRETRO", corePath)
            putExtra("IME", Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            putExtra("DATADIR", app.dataDir)
            putExtra("APK", app.sourceDir)
            putExtra("SDCARD", storage.absolutePath)
            putExtra("EXTERNAL", externalFiles.absolutePath)
            // Point RetroArch at its own existing config. The companion never
            // creates or edits this file, so mappings, remaps, and overrides remain intact.
            putExtra("CONFIGFILE", configPath)

            if (romReference == savedRom.toString() && savedRom.scheme == "content") {
                clipData = ClipData.newRawUri("Patched Archipelago GBA ROM", savedRom)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(intent)
        return resumeExisting
    }

    private fun normalizedAddress(address: String?): String = address
        ?.trim()
        ?.removePrefix("ws://")
        ?.removePrefix("wss://")
        ?.trimEnd('/')
        ?.lowercase()
        .orEmpty()

    private fun localPath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content" || uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parts = documentId.split(':', limit = 2)
        if (parts.size != 2) return null
        val root = if (parts[0].equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage", parts[0])
        }
        val rootPath = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(rootPath, parts[1]).canonicalFile }.getOrNull() ?: return null
        if (candidate.path != rootPath.path && !candidate.path.startsWith(rootPath.path + File.separator)) return null
        return candidate.absolutePath
    }
}
