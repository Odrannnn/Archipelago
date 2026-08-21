package eu.odran.archipelago

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

/** Launches a saved GameCube/Wii disc image in the Archipelago Dolphin fork. */
object DolphinLauncher {
    private const val MAIN_ACTIVITY = "org.dolphinemu.dolphinemu.ui.main.MainActivity"
    private val packages = listOf(
        "eu.odran.dolphin.archipelago",
        "eu.odran.dolphin.archipelago.debug",
    )

    fun isGameCubeGame(context: Context, game: String?): Boolean =
        !game.isNullOrBlank() && OfflineGenerator.bundledWorlds(context).any {
            it.game == game && it.platform == "GameCube"
        }

    fun isDiscImage(context: Context, uri: Uri): Boolean = isSupportedDiscName(
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty(),
    )

    fun launch(context: Context, savedImage: Uri) {
        context.contentResolver.openFileDescriptor(savedImage, "r")?.use { descriptor ->
            check(descriptor.statSize != 0L) { "The saved disc image is empty." }
        } ?: error("The saved disc image is no longer available.")

        val packageName = packages.firstOrNull { candidate ->
            runCatching { context.packageManager.getApplicationInfo(candidate, 0) }.isSuccess
        } ?: error("Install the Dolphin Archipelago Android fork first.")

        context.grantUriPermission(packageName, savedImage, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(packageName, MAIN_ACTIVITY)
            data = savedImage
            clipData = ClipData.newRawUri("Patched Archipelago disc image", savedImage)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
        })
    }

    internal fun isSupportedDiscName(name: String): Boolean =
        listOf(".iso", ".gcz", ".rvz", ".wia", ".wbfs", ".ciso").any {
            name.endsWith(it, ignoreCase = true)
        }
}
