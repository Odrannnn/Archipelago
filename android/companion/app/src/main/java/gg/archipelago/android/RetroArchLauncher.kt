package gg.archipelago.android

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
    private const val CORE_FILE_NAME = "mgba_apbridge_v5_libretro_android.so"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun launch(context: Context, savedRom: Uri) {
        val app = context.packageManager.getApplicationInfo(PACKAGE_NAME, 0)
        val storage = Environment.getExternalStorageDirectory()
        val romReference = localPath(savedRom) ?: savedRom.toString()
        val corePath = File(app.dataDir, "cores/$CORE_FILE_NAME").absolutePath

        val intent = Intent().apply {
            component = ComponentName(PACKAGE_NAME, ACTIVITY_NAME)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("ROM", romReference)
            putExtra("LIBRETRO", corePath)
            putExtra("IME", Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            putExtra("DATADIR", app.dataDir)
            putExtra("APK", app.sourceDir)
            putExtra("SDCARD", storage.absolutePath)
            putExtra("EXTERNAL", storage.absolutePath)

            if (romReference == savedRom.toString() && savedRom.scheme == "content") {
                clipData = ClipData.newRawUri("Patched Metroid Fusion ROM", savedRom)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(intent)
    }

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
