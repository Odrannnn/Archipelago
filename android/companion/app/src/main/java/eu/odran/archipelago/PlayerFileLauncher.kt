package eu.odran.archipelago

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

data class PlayerFileHandler(
    val extension: String,
    val gameName: String,
    val appName: String,
    val packageName: String,
    val activityName: String,
    val mimeType: String,
)

/** Hands native-client player files to Android game ports which implement their own AP connection. */
object PlayerFileLauncher {
    private val handlers = listOf(
        PlayerFileHandler(
            extension = ".apladxhd",
            gameName = "Links Awakening DX HD",
            appName = "LADXHD",
            packageName = "com.zelda.ladxhd",
            activityName = "com.zelda.ladxhd.ArchipelagoImportActivity",
            mimeType = "application/x-apladxhd",
        ),
    )

    fun handlerFor(fileName: String): PlayerFileHandler? = handlers.firstOrNull { handler ->
        fileName.endsWith(handler.extension, ignoreCase = true)
    }

    fun supports(fileName: String): Boolean = handlerFor(fileName) != null

    fun actionLabel(fileName: String): String = handlerFor(fileName)?.let { handler ->
        "Import into ${handler.appName}"
    } ?: "Open player file"

    fun launch(context: Context, playerFile: File) {
        require(playerFile.isFile) { "The generated player file is missing." }
        val handler = requireNotNull(handlerFor(playerFile.name)) {
            "No Android game is registered for ${playerFile.extension.ifBlank { "this player file" }}."
        }
        val sharedDirectory = File(context.cacheDir, "game_imports").apply {
            check(isDirectory || mkdirs()) { "Could not prepare the player-file sharing directory." }
        }
        val safeName = playerFile.name.takeIf { File(it).name == it && it.isNotBlank() }
            ?: "player${handler.extension}"
        val sharedFile = File(sharedDirectory, safeName)
        if (playerFile.canonicalFile != sharedFile.canonicalFile) {
            playerFile.copyTo(sharedFile, overwrite = true)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", sharedFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(handler.packageName, handler.activityName)
            setDataAndType(uri, handler.mimeType)
            clipData = ClipData.newRawUri("Archipelago player file", uri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        }
        check(context.packageManager.resolveActivity(intent, 0) != null) {
            "${handler.appName} is not installed, or this version does not support ${handler.extension} imports."
        }
        context.grantUriPermission(handler.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }
}
