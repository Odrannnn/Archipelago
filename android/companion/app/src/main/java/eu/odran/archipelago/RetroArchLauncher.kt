package eu.odran.archipelago

import android.content.ClipData
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import java.io.File

/** Launches a patched ROM directly with the custom mGBA Archipelago core. */
object RetroArchLauncher {
    private const val PACKAGE_NAME = "com.retroarch.aarch64"
    private const val ACTIVITY_NAME = "com.retroarch.browser.retroactivity.RetroActivityFuture"
    private const val CORE_FILE_NAME = "mgba_apbridge_v9_libretro_android.so"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

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
        val romReference = localPath(context, savedRom) ?: savedRom.toString()
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
                clipData = ClipData.newRawUri("Patched Archipelago ROM", savedRom)
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

    private fun localPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme != "content") return null
        queryDataPath(context, uri)?.let { return validatedExternalPath(it) }
        if (uri.authority == DOWNLOADS_AUTHORITY) {
            downloadsPath(context, uri)?.let { return it }
        }
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
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

    /** Resolve Android's Downloads provider, whose numeric document IDs are not filesystem paths. */
    private fun downloadsPath(context: Context, uri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (documentId.startsWith("raw:")) return validatedExternalPath(documentId.removePrefix("raw:"))

        val numericId = documentId.substringAfterLast(':').toLongOrNull()
        if (numericId != null) {
            listOf(
                "content://downloads/my_downloads",
                "content://downloads/all_downloads",
                "content://downloads/public_downloads",
                "content://media/external/downloads",
            ).firstNotNullOfOrNull { base ->
                queryDataPath(context, ContentUris.withAppendedId(Uri.parse(base), numericId))
                    ?.let(::validatedExternalPath)
            }?.let { return it }
        }

        // ACTION_CREATE_DOCUMENT defaults to the public Download directory on
        // this provider. RetroArch can read that same path when manual loading works.
        val displayName = queryDisplayName(context, uri) ?: return null
        if (displayName.isBlank() || File(displayName).name != displayName) return null
        val downloads = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)
        return validatedExternalPath(File(downloads, displayName).absolutePath)
    }

    private fun queryDataPath(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex("_data")
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()

    private fun validatedExternalPath(path: String): String? {
        val root = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (candidate.path != root.path && !candidate.path.startsWith(root.path + File.separator)) return null
        return candidate.absolutePath
    }
}
