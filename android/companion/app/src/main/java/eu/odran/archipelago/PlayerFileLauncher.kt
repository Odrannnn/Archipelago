package eu.odran.archipelago

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

data class PlayerFileHandler(
    val extension: String,
    val gameName: String,
    val appName: String,
    val packageName: String,
    val mimeType: String,
    val serverExtra: String,
    val passwordExtra: String,
    val saveSlotExtra: String,
)

data class PlayerFileLaunchOptions(
    val serverAddress: String? = null,
    val password: String? = null,
    val saveSlot: Int = 0,
)

/** Hands native-client player files to Android game ports which implement their own AP connection. */
object PlayerFileLauncher {
    private val handlers = listOf(
        PlayerFileHandler(
            extension = ".apladxhd",
            gameName = "Links Awakening DX HD",
            appName = "LADXHD",
            packageName = "com.zelda.ladxhd",
            mimeType = "application/x-apladxhd",
            serverExtra = "com.zelda.ladxhd.extra.SERVER",
            passwordExtra = "com.zelda.ladxhd.extra.PASSWORD",
            saveSlotExtra = "com.zelda.ladxhd.extra.SAVE_SLOT",
        ),
    )

    fun handlerFor(fileName: String): PlayerFileHandler? = handlers.firstOrNull { handler ->
        fileName.endsWith(handler.extension, ignoreCase = true)
    }

    fun handlerForGame(gameName: String?): PlayerFileHandler? = handlers.firstOrNull { handler ->
        gameName.equals(handler.gameName, ignoreCase = true)
    }

    fun supports(fileName: String): Boolean = handlerFor(fileName) != null

    fun actionLabel(fileName: String): String = handlerFor(fileName)?.let { handler ->
        "Import into ${handler.appName}"
    } ?: "Open player file"

    fun launch(
        context: Context,
        playerFile: File,
        options: PlayerFileLaunchOptions = PlayerFileLaunchOptions(),
    ) {
        require(playerFile.isFile) { "The generated player file is missing." }
        val handler = requireNotNull(handlerFor(playerFile.name)) {
            "No Android game is registered for ${playerFile.extension.ifBlank { "this player file" }}."
        }
        launchSharedFile(context, playerFile, handler, options)
    }

    fun launch(
        context: Context,
        source: android.net.Uri,
        fileName: String,
        options: PlayerFileLaunchOptions = PlayerFileLaunchOptions(),
    ) {
        val safeName = File(fileName).name
        require(safeName.isNotBlank() && safeName == fileName) { "The selected player filename is invalid." }
        val handler = requireNotNull(handlerFor(safeName)) {
            "Select a supported native player file."
        }
        val sharedDirectory = File(context.cacheDir, "game_imports").apply {
            check(isDirectory || mkdirs()) { "Could not prepare the player-file sharing directory." }
        }
        val sharedFile = File(sharedDirectory, safeName)
        context.contentResolver.openInputStream(source)?.buffered()?.use { input ->
            sharedFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied += count
                    require(copied <= MAX_PLAYER_FILE_BYTES) { "The selected player file is too large." }
                    output.write(buffer, 0, count)
                }
                require(copied > 0) { "The selected player file is empty." }
            }
        } ?: error("Could not open the selected player file.")
        launchSharedFile(context, sharedFile, handler, options)
    }

    fun embeddedPlayerName(playerFile: File): String? {
        val handler = handlerFor(playerFile.name) ?: return null
        return when (handler.extension) {
            ".apladxhd" -> runCatching {
                JSONObject(playerFile.readText(Charsets.UTF_8)).optString("slot_name").trim()
                    .takeIf { it.isNotBlank() }
            }.getOrNull()
            else -> null
        }
    }

    internal fun normalizedServerAddress(address: String): String = address
        .trim()
        .replace(Regex("^[A-Za-z][A-Za-z0-9+.-]*://"), "")
        .substringBefore('/')
        .trimEnd('/')

    private fun launchSharedFile(
        context: Context,
        sharedFile: File,
        handler: PlayerFileHandler,
        options: PlayerFileLaunchOptions,
    ) {
        val stagedFile = if (sharedFile.parentFile == File(context.cacheDir, "game_imports")) {
            sharedFile
        } else {
            val directory = File(context.cacheDir, "game_imports").apply {
                check(isDirectory || mkdirs()) { "Could not prepare the player-file sharing directory." }
            }
            File(directory, sharedFile.name).also { destination ->
                if (sharedFile.canonicalFile != destination.canonicalFile) {
                    sharedFile.copyTo(destination, overwrite = true)
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", stagedFile)
        require(options.saveSlot in 0..3) { "The LADXHD save position must be between 0 and 3." }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = handler.mimeType
            setPackage(handler.packageName)
            putExtra(Intent.EXTRA_STREAM, uri)
            options.serverAddress
                ?.let(::normalizedServerAddress)
                ?.takeIf { it.isNotBlank() }
                ?.let { putExtra(handler.serverExtra, it) }
            options.password?.let { putExtra(handler.passwordExtra, it) }
            putExtra(handler.saveSlotExtra, options.saveSlot)
            clipData = ClipData.newUri(context.contentResolver, "LADXHD seed", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        check(context.packageManager.resolveActivity(intent, 0) != null) {
            "${handler.appName} is not installed, or this version does not support ${handler.extension} imports."
        }
        context.grantUriPermission(handler.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private const val MAX_PLAYER_FILE_BYTES = 64L * 1024 * 1024
}
