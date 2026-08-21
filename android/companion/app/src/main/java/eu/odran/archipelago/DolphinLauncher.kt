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

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    fun isDiscImage(context: Context, uri: Uri): Boolean =
        displayName(context, uri)?.let(::isSupportedDiscName) == true

    internal fun readGameCubeDiscIdentity(
        context: Context,
        uri: Uri,
        requireIsoName: Boolean = true,
    ): GameCubeDiscIdentity {
        require(!requireIsoName || displayName(context, uri)?.endsWith(".iso", ignoreCase = true) == true) {
            "Select an uncompressed GameCube ISO whose filename ends in .iso."
        }
        val header = context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            ByteArray(GAMECUBE_HEADER_BYTES).also { bytes ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count < 0) break
                    if (count == 0) continue
                    offset += count
                }
                require(offset == bytes.size) { "The selected disc image is too small." }
            }
        } ?: error("Could not read the selected disc image.")
        return parseGameCubeDiscIdentity(header)
    }

    fun launch(context: Context, savedImage: Uri) {
        require(isDiscImage(context, savedImage)) {
            "Rename the disc image so its filename ends in .iso, .gcm, .rvz, .wia, .wbfs, .ciso, or .gcz."
        }
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
            // Preserve a running emulation task. The custom Dolphin entry point
            // brings its existing EmulationActivity forward when this URI is
            // received while a game is already running.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    internal fun isSupportedDiscName(name: String): Boolean =
        listOf(".iso", ".gcm", ".gcz", ".rvz", ".wia", ".wbfs", ".ciso").any {
            name.endsWith(it, ignoreCase = true)
        }

    internal fun discMimeType(name: String): String = when {
        name.endsWith(".iso", ignoreCase = true) -> "application/x-iso9660-image"
        else -> "application/octet-stream"
    }

    internal fun parseGameCubeDiscIdentity(header: ByteArray): GameCubeDiscIdentity {
        require(header.size >= GAMECUBE_HEADER_BYTES) { "The selected disc image is too small." }
        require(header.copyOfRange(0x1c, 0x20).contentEquals(GAMECUBE_MAGIC)) {
            "The selected file does not have a valid GameCube disc header."
        }
        val gameIdBytes = header.copyOfRange(0, 6)
        require(gameIdBytes.all { byte -> (byte.toInt() and 0xff) in 0x21..0x7e }) {
            "The selected file has an invalid GameCube game ID."
        }
        return GameCubeDiscIdentity(
            gameId = gameIdBytes.toString(Charsets.US_ASCII),
            discNumber = header[6].toInt() and 0xff,
            revision = header[7].toInt() and 0xff,
        )
    }

    private const val GAMECUBE_HEADER_BYTES = 0x20
    private val GAMECUBE_MAGIC = byteArrayOf(0xc2.toByte(), 0x33, 0x9f.toByte(), 0x3d)
}

internal data class GameCubeDiscIdentity(
    val gameId: String,
    val discNumber: Int,
    val revision: Int,
)
