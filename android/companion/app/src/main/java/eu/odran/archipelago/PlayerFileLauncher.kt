package eu.odran.archipelago

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

sealed interface PlayerFileIntentContract {
    data class SendStream(
        val serverExtra: String,
        val passwordExtra: String,
        val saveSlotExtra: String,
    ) : PlayerFileIntentContract

    data class ArchipelagoView(
        val activityClassName: String,
    ) : PlayerFileIntentContract
}

data class PlayerFileHandler(
    val extension: String,
    val gameName: String,
    val appName: String,
    val packageName: String,
    val mimeType: String? = null,
    val intentContract: PlayerFileIntentContract,
)

data class PlayerFileLaunchOptions(
    val serverAddress: String? = null,
    val slotName: String? = null,
    val password: String? = null,
    val saveSlot: Int = 0,
)

data class PlayerFileServerTarget(
    val host: String,
    val port: Int,
    val secure: Boolean,
)

/** Hands native-client player files to Android game ports which implement their own AP connection. */
object PlayerFileLauncher {
    private val handlers = listOf(
        PlayerFileHandler(
            extension = ".apladxhd",
            gameName = "Links Awakening DX HD",
            appName = "LADXHD",
            packageName = "com.zelda.ladxhd.archipelago",
            mimeType = "application/x-apladxhd",
            intentContract = PlayerFileIntentContract.SendStream(
                serverExtra = "com.zelda.ladxhd.extra.SERVER",
                passwordExtra = "com.zelda.ladxhd.extra.PASSWORD",
                saveSlotExtra = "com.zelda.ladxhd.extra.SAVE_SLOT",
            ),
        ),
        PlayerFileHandler(
            extension = ".aptmc",
            gameName = "The Minish Cap",
            appName = "The Minish Cap Android",
            packageName = "dev.picori.tmc",
            intentContract = PlayerFileIntentContract.ArchipelagoView(
                activityClassName = "dev.picori.tmc.TMCActivity",
            ),
        ),
    )

    fun handlerFor(fileName: String): PlayerFileHandler? = handlers.firstOrNull { handler ->
        fileName.endsWith(handler.extension, ignoreCase = true)
    }

    fun handlerForGame(gameName: String?): PlayerFileHandler? = handlers.firstOrNull { handler ->
        gameName.equals(handler.gameName, ignoreCase = true)
    }

    fun supports(fileName: String): Boolean = handlerFor(fileName) != null

    fun actionLabel(fileName: String): String = handlerFor(fileName)?.let(::actionLabel) ?: "Open player file"

    fun actionLabel(handler: PlayerFileHandler): String = when (handler.intentContract) {
        is PlayerFileIntentContract.SendStream -> "Import into ${handler.appName}"
        is PlayerFileIntentContract.ArchipelagoView -> "Launch in ${handler.appName}"
    }

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

    fun launch(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        options: PlayerFileLaunchOptions = PlayerFileLaunchOptions(),
    ) {
        val safeName = File(fileName).name
        require(safeName.isNotBlank() && safeName == fileName) { "The player filename is invalid." }
        val handler = requireNotNull(handlerFor(safeName)) {
            "No Android game is registered for this player file."
        }
        require(bytes.isNotEmpty()) { "The player file is empty." }
        require(bytes.size <= MAX_PLAYER_FILE_BYTES) { "The player file is too large." }
        require(declaredGame(safeName, bytes) == handler.gameName) {
            "The player file is for a different game."
        }
        val sharedDirectory = File(context.cacheDir, "game_imports").apply {
            check(isDirectory || mkdirs()) { "Could not prepare the player-file sharing directory." }
        }
        val sharedFile = File(sharedDirectory, safeName).apply { writeBytes(bytes) }
        launchSharedFile(context, sharedFile, handler, options)
    }

    internal fun declaredGame(fileName: String, bytes: ByteArray): String? {
        val handler = handlerFor(fileName) ?: return null
        return when (handler.extension) {
            ".apladxhd" -> JSONObject(bytes.toString(Charsets.UTF_8)).optString("game").trim().let { game ->
                require(game.equals(handler.gameName, ignoreCase = true)) {
                    "The LADXHD seed manifest declares an invalid game."
                }
                handler.gameName
            }
            ".aptmc" -> patchManifest(bytes).optString("game").trim().let { game ->
                require(game.equals(handler.gameName, ignoreCase = true)) {
                    "The Minish Cap player file declares an invalid game."
                }
                handler.gameName
            }
            else -> null
        }
    }

    fun embeddedPlayerName(playerFile: File): String? {
        val handler = handlerFor(playerFile.name) ?: return null
        return when (handler.extension) {
            ".apladxhd" -> runCatching {
                JSONObject(playerFile.readText(Charsets.UTF_8)).optString("slot_name").trim()
                    .takeIf { it.isNotBlank() }
            }.getOrNull()
            ".aptmc" -> runCatching {
                ZipFile(playerFile).use { archive ->
                    val manifest = archive.getEntry("archipelago.json") ?: return@use null
                    archive.getInputStream(manifest).bufferedReader(Charsets.UTF_8).use { reader ->
                        JSONObject(reader.readText()).optString("player_name").trim()
                            .takeIf { it.isNotBlank() }
                    }
                }
            }.getOrNull()
            else -> null
        }
    }

    internal fun normalizedServerAddress(address: String): String = address
        .trim()
        .replace(Regex("^[A-Za-z][A-Za-z0-9+.-]*://"), "")
        .substringBefore('/')
        .trimEnd('/')

    internal fun serverTarget(address: String): PlayerFileServerTarget? = runCatching {
        val raw = address.trim()
        if (raw.isBlank()) return null
        val explicitScheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")
            .find(raw)?.groupValues?.get(1)?.lowercase()
        val parsed = URI(if (explicitScheme == null) "ap://$raw" else raw)
        val host = parsed.host?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val secure = when (explicitScheme) {
            "wss", "https" -> true
            "ws", "http" -> false
            else -> parsed.port == 443 || host.equals("archipelago.gg", ignoreCase = true) ||
                host.endsWith(".archipelago.gg", ignoreCase = true)
        }
        PlayerFileServerTarget(
            host = host,
            port = parsed.port.takeIf { it in 1..65535 } ?: if (secure) 443 else 38281,
            secure = secure,
        )
    }.getOrNull()

    private fun patchManifest(bytes: ByteArray): JSONObject {
        ZipInputStream(ByteArrayInputStream(bytes)).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.name == "archipelago.json") {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = archive.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        require(total <= MAX_MANIFEST_BYTES) { "The player-file manifest is too large." }
                        output.write(buffer, 0, count)
                    }
                    return JSONObject(output.toString(Charsets.UTF_8.name()))
                }
            }
        }
        error("The player file does not contain archipelago.json.")
    }

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
        val intent = when (val contract = handler.intentContract) {
            is PlayerFileIntentContract.SendStream -> {
                require(options.saveSlot in 0..3) { "The LADXHD save position must be between 0 and 3." }
                Intent(Intent.ACTION_SEND).apply {
                    type = requireNotNull(handler.mimeType)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    options.serverAddress
                        ?.let(::normalizedServerAddress)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { putExtra(contract.serverExtra, it) }
                    options.password?.let { putExtra(contract.passwordExtra, it) }
                    putExtra(contract.saveSlotExtra, options.saveSlot)
                    clipData = ClipData.newUri(context.contentResolver, "${handler.appName} seed", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            is PlayerFileIntentContract.ArchipelagoView -> Intent(Intent.ACTION_VIEW).apply {
                setClassName(handler.packageName, contract.activityClassName)
                data = uri
                val target = options.serverAddress?.let(::serverTarget)
                val selectedSlot = options.slotName?.trim().orEmpty()
                if (target != null) {
                    putExtra("ap_host", target.host)
                    putExtra("ap_port", target.port)
                    putExtra("ap_secure", target.secure)
                }
                putExtra("ap_slot", selectedSlot)
                putExtra("ap_password", options.password.orEmpty())
                putExtra("ap_connect", target != null && selectedSlot.isNotBlank())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val targetPackage = when (handler.intentContract) {
            is PlayerFileIntentContract.SendStream -> handler.packageName.takeIf {
                intent.setPackage(handler.packageName)
                context.packageManager.resolveActivity(intent, 0) != null
            }
            is PlayerFileIntentContract.ArchipelagoView ->
                handler.packageName.takeIf { context.packageManager.resolveActivity(intent, 0) != null }
        }
        check(targetPackage != null) {
            "${handler.appName} is not installed, or this version does not support ${handler.extension} imports."
        }
        if (handler.intentContract is PlayerFileIntentContract.SendStream) intent.setPackage(targetPackage)
        context.grantUriPermission(targetPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private const val MAX_PLAYER_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024
}
