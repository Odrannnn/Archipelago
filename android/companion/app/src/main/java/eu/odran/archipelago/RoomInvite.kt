package eu.odran.archipelago

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class RoomInvite(
    val roomId: String,
    val seedId: String,
    val playerSlot: Int? = null,
    val playerName: String? = null,
    val patchName: String? = null,
    val patchBytes: ByteArray? = null,
    val gameName: String? = null,
) {
    val hasPlayerIdentity: Boolean
        get() = playerSlot != null && !playerName.isNullOrBlank() && !gameName.isNullOrBlank()
    val hasPlayerPatch: Boolean
        get() = hasPlayerIdentity && !patchName.isNullOrBlank() && patchBytes != null
    val hasNativePlayerFile: Boolean
        get() = hasPlayerPatch && PlayerFileLauncher.handlerFor(patchName.orEmpty()) != null
    private fun metadataJson(version: Int) = JSONObject().apply {
        put("format", FORMAT)
        put("version", version)
        put("room_id", roomId)
        put("seed_id", seedId)
        if (version >= LEGACY_PATCH_VERSION) {
            put("player_slot", playerSlot)
            put("player_name", playerName)
            if (version >= CURRENT_VERSION) put("game", gameName)
        }
        if (hasPlayerPatch) {
            put("patch_name", patchName)
            put("patch_game", gameName)
            put("patch_entry", PATCH_ENTRY)
            put("patch_sha256", patchBytes?.sha256())
        }
    }.toString(2)

    companion object {
        const val MIME_TYPE = "application/vnd.gg.archipelago.companion-invite"
        const val URI_SCHEME = "archipelago-companion"
        private const val FORMAT = "gg.archipelago.android.room-invite"
        private const val LEGACY_PATCH_VERSION = 3
        private const val CURRENT_VERSION = 4
        private const val METADATA_ENTRY = "invite.json"
        private const val PATCH_ENTRY = "player.patch"
        private const val MAX_METADATA_BYTES = 64 * 1024
        private const val MAX_PATCH_BYTES = 32 * 1024 * 1024
        private const val MAX_INVITE_BYTES = 40 * 1024 * 1024

        fun share(
            context: Context,
            room: HostedRoom,
            playerSlot: Int,
            playerName: String,
            patchName: String,
            patchBytes: ByteArray,
        ) = sharePlayer(
            context,
            room,
            playerSlot,
            playerName,
            playerFileGame(patchName, patchBytes),
            patchName,
            patchBytes,
        )

        fun sharePatchless(
            context: Context,
            room: HostedRoom,
            playerSlot: Int,
            playerName: String,
            gameName: String,
        ) = sharePlayer(context, room, playerSlot, playerName, gameName, null, null)

        private fun sharePlayer(
            context: Context,
            room: HostedRoom,
            playerSlot: Int,
            playerName: String,
            gameName: String,
            patchName: String?,
            patchBytes: ByteArray?,
        ) {
            require(playerSlot > 0) { "Invalid player slot." }
            require(playerName.isNotBlank() && playerName.length <= 256) { "Invalid player name." }
            require(gameName.isNotBlank() && gameName.length <= 256) { "Invalid player game." }
            require((patchName == null) == (patchBytes == null)) { "Incomplete player patch." }
            val safePatchName = patchName?.let {
                File(it).name.also { name -> require(name.isNotBlank()) { "Invalid player patch name." } }
            }
            patchBytes?.let {
                require(it.isNotEmpty() && it.size <= MAX_PATCH_BYTES) { "The player patch is too large." }
                require(playerFileGame(checkNotNull(safePatchName), it) == gameName) {
                    "The player file is for a different game."
                }
            }
            val invite = RoomInvite(
                roomId = room.roomId,
                seedId = room.seedId,
                playerSlot = playerSlot,
                playerName = playerName,
                patchName = safePatchName,
                patchBytes = patchBytes,
                gameName = gameName,
            )
            val directory = File(context.cacheDir, "shared_invites").apply { mkdirs() }
            directory.listFiles()?.filter { it.isFile && it.name.endsWith(".apinvite") }?.forEach { it.delete() }
            val safePlayer = playerName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(32)
                .ifBlank { "Player$playerSlot" }
            val file = File(directory, "Archipelago-${room.roomId.take(8)}-$safePlayer.apinvite")
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(METADATA_ENTRY))
                zip.write(invite.metadataJson(CURRENT_VERSION).toByteArray())
                zip.closeEntry()
                patchBytes?.let {
                    zip.putNextEntry(ZipEntry(PATCH_ENTRY))
                    zip.write(it)
                    zip.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

            val roomUrl = "${ArchipelagoWebHostClient.BASE_URL}/room/${room.roomId}"
            val appLink = "$URI_SCHEME://join/${room.roomId}"
            val trackerUrl = room.trackerId.takeIf { it.isNotBlank() }?.let {
                "${ArchipelagoWebHostClient.BASE_URL}/tracker/$it"
            }
            val message = buildString {
                appendLine("Join my Archipelago multiplayer seed as $playerName (slot $playerSlot)!")
                appendLine()
                if (invite.hasNativePlayerFile) {
                    appendLine("The attached companion invite contains your $gameName native player file.")
                } else if (invite.hasPlayerPatch) {
                    appendLine("The attached companion invite contains your $gameName player patch.")
                } else {
                    appendLine("$gameName does not need a player patch; the invite contains your player connection details.")
                }
                appendLine("Room-only fallback: $appLink")
                appendLine("Room and player patches: $roomUrl")
                if (room.lastPort > 0) appendLine("Server: archipelago.gg:${room.lastPort}")
                trackerUrl?.let { appendLine("Tracker: $it") }
                if (room.players.isNotEmpty()) appendLine("Players: ${room.players.joinToString()}")
                append("Open the attached .apinvite with Archipelago Companion. ")
                append(
                    if (invite.hasNativePlayerFile) {
                        "It will open the player file in the installed game with the room connection pre-filled."
                    } else if (invite.hasPlayerPatch) {
                        "It will reuse your cached clean base ROM or ask for it once."
                    } else {
                        "It will load the room and make the game's launch action available."
                    },
                )
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_SUBJECT, "Archipelago multiplayer invitation")
                putExtra(Intent.EXTRA_TEXT, message)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "Archipelago invitation", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share Archipelago invitation"))
        }

        fun fromIntent(context: Context, intent: Intent?): RoomInvite? {
            intent ?: return null
            if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == URI_SCHEME) {
                val roomId = intent.data?.lastPathSegment.orEmpty()
                validateRoomId(roomId)
                return RoomInvite(roomId, "")
            }
            val uri = when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                else -> null
            } ?: return null
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_INVITE_BYTES + 1) }
                ?: error("Could not read the shared invitation.")
            require(bytes.size <= MAX_INVITE_BYTES) { "The shared invitation is too large." }
            require(bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                "Only player-specific Archipelago Companion invitation packages are supported."
            }
            return parsePlayerPackage(bytes)
        }

        private fun parsePlayerPackage(bytes: ByteArray): RoomInvite {
            var metadata: ByteArray? = null
            var patch: ByteArray? = null
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && '/' !in entry.name && '\\' !in entry.name) {
                        "The invitation contains an invalid entry."
                    }
                    when (entry.name) {
                        METADATA_ENTRY -> {
                            require(metadata == null) { "The invitation contains duplicate metadata." }
                            metadata = zip.readAtMost(MAX_METADATA_BYTES + 1).also {
                                require(it.size <= MAX_METADATA_BYTES) { "The invitation metadata is too large." }
                            }
                        }
                        PATCH_ENTRY -> {
                            require(patch == null) { "The invitation contains duplicate player patches." }
                            patch = zip.readAtMost(MAX_PATCH_BYTES + 1).also {
                                require(it.size <= MAX_PATCH_BYTES) { "The player patch is too large." }
                            }
                        }
                        else -> error("The invitation contains an unsupported entry: ${entry.name}")
                    }
                    zip.closeEntry()
                }
            }
            val root = JSONObject((metadata ?: error("The invitation has no metadata.")).toString(Charsets.UTF_8))
            val version = root.optInt("version")
            require(root.optString("format") == FORMAT && version in LEGACY_PATCH_VERSION..CURRENT_VERSION) {
                "This is not a supported player-specific invitation."
            }
            val roomId = root.getString("room_id")
            validateRoomId(roomId)
            val playerSlot = root.getInt("player_slot")
            require(playerSlot > 0) { "The invitation contains an invalid player slot." }
            val playerName = root.getString("player_name").trim()
            require(playerName.isNotBlank() && playerName.length <= 256) { "The invitation contains an invalid player name." }
            if (version == LEGACY_PATCH_VERSION) require(patch != null) {
                "The legacy invitation has no player patch."
            }
            val patchName = root.optString("patch_name").takeIf { it.isNotBlank() }?.let { File(it).name }
            require((patchName == null) == (patch == null)) { "The invitation has incomplete player patch data." }
            val gameName = if (patch != null) {
                require(patch.isNotEmpty()) { "The invitation contains an empty player patch." }
                require(root.optString("patch_entry") == PATCH_ENTRY) { "The invitation patch entry is invalid." }
                require(patch.sha256().equals(root.getString("patch_sha256"), ignoreCase = true)) {
                    "The player patch failed its integrity check."
                }
                playerFileGame(checkNotNull(patchName), patch).also { actualGame ->
                    root.optString("patch_game").takeIf { it.isNotBlank() && it != "null" }?.let { declaredGame ->
                        require(declaredGame == actualGame) {
                            "The invitation's patch game metadata does not match its patch."
                        }
                    }
                    root.optString("game").takeIf { it.isNotBlank() }?.let { declaredGame ->
                        require(declaredGame == actualGame) {
                            "The invitation's player game metadata does not match its patch."
                        }
                    }
                }
            } else {
                require(
                    root.optString("patch_entry").isBlank() &&
                        root.optString("patch_sha256").isBlank() &&
                        root.optString("patch_game").isBlank(),
                ) { "The patchless invitation contains unexpected patch metadata." }
                root.optString("game").trim().also {
                    require(it.isNotBlank() && it.length <= 256) {
                        "The patchless invitation does not declare a valid game."
                    }
                }
            }
            return RoomInvite(
                roomId = roomId,
                seedId = root.optString("seed_id"),
                playerSlot = playerSlot,
                playerName = playerName,
                patchName = patchName,
                patchBytes = patch,
                gameName = gameName,
            )
        }

        private fun validateRoomId(roomId: String) {
            require(ArchipelagoWebHostClient.ROOM_ID_PATTERN.matches(roomId)) {
                "The invitation contains an invalid room identifier."
            }
        }
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = maxBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}

data class JoinedRoom(
    val roomId: String,
    val trackerId: String,
    val port: Int,
    val players: List<String>,
    val updatedAt: Long,
    val playerSlot: Int? = null,
    val playerName: String? = null,
    val patchedRomName: String? = null,
    val patchedRomUri: String? = null,
    val gameName: String = "",
    val patchedRomSha256: String? = null,
    val forceLocalItemsFromServer: Boolean = false,
)

object JoinedRoomStore {
    private const val PREFERENCES = "joined_archipelago_room"
    private const val LEGACY_ROOM = "room"
    private const val ROOMS = "rooms"
    private const val ACTIVE_ROOM_ID = "active_room_id"

    @Synchronized
    fun save(context: Context, room: HostedRoom, invite: RoomInvite? = null): JoinedRoom {
        val rooms = loadAll(context).toMutableList()
        val previous = rooms.firstOrNull { it.roomId == room.roomId }
        val selectedSlot = invite?.playerSlot ?: previous?.playerSlot
        val selectedName = invite?.playerName ?: previous?.playerName
        val previousPlayerRom = previous?.takeIf { it.playerSlot == selectedSlot }
        val availableGames = OfflineGenerator.availableGames(context)
        val selectedGame = invite?.gameName ?: previous?.gameName ?: room.players.firstNotNullOfOrNull { player ->
            availableGames.firstOrNull { game -> player.endsWith(" ($game)") }
        }.orEmpty()
        val joined = JoinedRoom(
            room.roomId,
            room.trackerId,
            room.lastPort,
            room.players,
            System.currentTimeMillis(),
            selectedSlot,
            selectedName,
            previousPlayerRom?.patchedRomName,
            previousPlayerRom?.patchedRomUri,
            selectedGame,
            previousPlayerRom?.patchedRomSha256,
            previous?.forceLocalItemsFromServer == true,
        )
        rooms.removeAll { it.roomId == joined.roomId }
        rooms += joined
        persist(context, rooms, joined.roomId)
        return joined
    }

    @Synchronized
    fun rememberPatchedRom(context: Context, name: String, uri: Uri, sha256: String?): JoinedRoom? {
        if (sha256 != null) {
            require(SHA256_PATTERN.matches(sha256)) { "Invalid patched ROM fingerprint." }
        }
        val current = load(context) ?: return null
        val updated = current.copy(
            patchedRomName = File(name).name,
            patchedRomUri = uri.toString(),
            patchedRomSha256 = sha256?.lowercase(),
        )
        val rooms = loadAll(context).filterNot { it.roomId == updated.roomId } + updated
        persist(context, rooms, updated.roomId)
        return updated
    }

    @Synchronized
    fun setForceLocalItemsFromServer(context: Context, roomId: String, enabled: Boolean): JoinedRoom? {
        val current = loadAll(context).firstOrNull { it.roomId == roomId } ?: return null
        val updated = current.copy(forceLocalItemsFromServer = enabled)
        val rooms = loadAll(context).filterNot { it.roomId == roomId } + updated
        persist(context, rooms, roomId)
        return updated
    }

    @Synchronized
    fun select(context: Context, roomId: String): JoinedRoom? {
        val selected = loadAll(context).firstOrNull { it.roomId == roomId } ?: return null
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(ACTIVE_ROOM_ID, selected.roomId).apply()
        return selected
    }

    @Synchronized
    fun delete(context: Context, roomId: String): JoinedRoom? {
        val rooms = loadAll(context).filterNot { it.roomId == roomId }
        val activeId = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ACTIVE_ROOM_ID, null)
        val nextActive = if (activeId == roomId) {
            rooms.maxByOrNull { it.updatedAt }
        } else {
            rooms.firstOrNull { it.roomId == activeId } ?: rooms.maxByOrNull { it.updatedAt }
        }
        persist(context, rooms, nextActive?.roomId)
        return nextActive
    }

    @Synchronized
    fun load(context: Context): JoinedRoom? {
        val rooms = loadAll(context)
        val activeId = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ACTIVE_ROOM_ID, null)
        return rooms.firstOrNull { it.roomId == activeId } ?: rooms.maxByOrNull { it.updatedAt }
    }

    @Synchronized
    fun loadAll(context: Context): List<JoinedRoom> {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val storedRooms = preferences.getString(ROOMS, null)
        if (storedRooms != null) {
            return runCatching {
                val data = JSONArray(storedRooms)
                List(data.length()) { index -> roomFromJson(data.getJSONObject(index)) }
                    .distinctBy { it.roomId }
                    .sortedByDescending { it.updatedAt }
            }.getOrDefault(emptyList())
        }

        val legacy = preferences.getString(LEGACY_ROOM, null)?.let { raw ->
            runCatching { roomFromJson(JSONObject(raw)) }.getOrNull()
        }
        val migrated = listOfNotNull(legacy)
        persist(context, migrated, legacy?.roomId)
        return migrated
    }

    private fun persist(context: Context, rooms: List<JoinedRoom>, activeRoomId: String?) {
        val data = JSONArray().apply {
            rooms.sortedByDescending { it.updatedAt }.forEach { put(it.toJson()) }
        }
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ROOMS, data.toString())
            .remove(LEGACY_ROOM)
        if (activeRoomId == null) editor.remove(ACTIVE_ROOM_ID)
        else editor.putString(ACTIVE_ROOM_ID, activeRoomId)
        editor.apply()
    }

    private fun JoinedRoom.toJson() = JSONObject().apply {
        put("roomId", roomId)
        put("trackerId", trackerId)
        put("port", port)
        put("players", JSONArray(players))
        put("updatedAt", updatedAt)
        put("playerSlot", playerSlot)
        put("playerName", playerName)
        put("patchedRomName", patchedRomName)
        put("patchedRomUri", patchedRomUri)
        put("gameName", gameName)
        put("patchedRomSha256", patchedRomSha256)
        put("forceLocalItemsFromServer", forceLocalItemsFromServer)
    }

    private fun roomFromJson(data: JSONObject) = JoinedRoom(
        data.getString("roomId"),
        data.optString("trackerId"),
        data.optInt("port", 0),
        data.optJSONArray("players")?.let { players ->
            List(players.length()) { players.getString(it) }
        }.orEmpty(),
        data.optLong("updatedAt", 0L),
        data.optInt("playerSlot", 0).takeIf { it > 0 },
        data.optString("playerName").takeIf { it.isNotBlank() && it != "null" },
        data.optString("patchedRomName").takeIf { it.isNotBlank() && it != "null" },
        data.optString("patchedRomUri").takeIf { it.isNotBlank() && it != "null" },
        data.optString("gameName")
            .takeIf { it.isNotBlank() && it != "null" }
            .orEmpty(),
        data.optString("patchedRomSha256")
            .takeIf { SHA256_PATTERN.matches(it) }
            ?.lowercase(),
        data.optBoolean("forceLocalItemsFromServer", false),
    )

    private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
}

/** Reads either a native player manifest or a standard AP patch manifest. */
private fun playerFileGame(fileName: String, patch: ByteArray): String {
    PlayerFileLauncher.declaredGame(fileName, patch)?.let { return it }
    ZipInputStream(ByteArrayInputStream(patch)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(!entry.isDirectory && '/' !in entry.name && '\\' !in entry.name) {
                "The player patch contains an invalid entry."
            }
            when (entry.name) {
                "archipelago.json" -> {
                    val manifestBytes = zip.readAtMost(MAX_PATCH_MANIFEST_BYTES + 1)
                    require(manifestBytes.size <= MAX_PATCH_MANIFEST_BYTES) { "The patch manifest is too large." }
                    val game = JSONObject(manifestBytes.toString(Charsets.UTF_8)).optString("game").trim()
                    require(game.isNotBlank()) { "The Archipelago patch does not declare a game." }
                    return game
                }
            }
            zip.closeEntry()
        }
    }
    error("The selected file has no standard Archipelago patch manifest.")
}

private const val MAX_PATCH_MANIFEST_BYTES = 64 * 1024

/** Remembers which local generated seed supplied each website-hosted room. */
object HostedRoomHistoryLinks {
    private const val PREFERENCES = "hosted_room_history_links"

    fun save(context: Context, roomId: String, historyId: String) {
        require(ArchipelagoWebHostClient.ROOM_ID_PATTERN.matches(roomId)) { "Invalid room identifier." }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(roomId, historyId).apply()
    }

    fun historyId(context: Context, roomId: String): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(roomId, null)

    fun remove(context: Context, roomId: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(roomId).apply()
    }
}

/** Restricts outgoing invitation URIs to the configured cache subdirectory. */
class InviteFileProvider : FileProvider()
