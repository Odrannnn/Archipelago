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
import java.io.File
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
    val hasPlayerPatch: Boolean
        get() = playerSlot != null && !playerName.isNullOrBlank() && !patchName.isNullOrBlank() && patchBytes != null
    private fun metadataJson(version: Int) = JSONObject().apply {
        put("format", FORMAT)
        put("version", version)
        put("room_id", roomId)
        put("seed_id", seedId)
        if (version >= PLAYER_PATCH_VERSION) {
            put("player_slot", playerSlot)
            put("player_name", playerName)
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
        private const val PLAYER_PATCH_VERSION = 2
        private const val METADATA_ENTRY = "invite.json"
        private const val PATCH_ENTRY = "player.apmetfus"
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
        ) {
            require(playerSlot > 0) { "Invalid player slot." }
            require(patchBytes.isNotEmpty() && patchBytes.size <= MAX_PATCH_BYTES) { "The player patch is too large." }
            val safePatchName = File(patchName).name
            val gameName = patchGame(patchBytes)
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
                zip.write(invite.metadataJson(PLAYER_PATCH_VERSION).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(PATCH_ENTRY))
                zip.write(patchBytes)
                zip.closeEntry()
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
                appendLine("The attached companion invite contains your $gameName player patch.")
                appendLine("Room-only fallback: $appLink")
                appendLine("Room and player patches: $roomUrl")
                if (room.lastPort > 0) appendLine("Server: archipelago.gg:${room.lastPort}")
                trackerUrl?.let { appendLine("Tracker: $it") }
                if (room.players.isNotEmpty()) appendLine("Players: ${room.players.joinToString()}")
                append("Open the attached .apinvite with Archipelago Companion. It will reuse your cached clean base ROM or ask for it once.")
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
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_INVITE_BYTES + 1) }
                ?: error("Could not read the shared invitation.")
            require(bytes.size <= MAX_INVITE_BYTES) { "The shared invitation is too large." }
            require(bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                "Only player-specific version 2 Archipelago Companion invitations are supported."
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
                            metadata = zip.readNBytes(MAX_METADATA_BYTES + 1).also {
                                require(it.size <= MAX_METADATA_BYTES) { "The invitation metadata is too large." }
                            }
                        }
                        PATCH_ENTRY -> {
                            require(patch == null) { "The invitation contains duplicate player patches." }
                            patch = zip.readNBytes(MAX_PATCH_BYTES + 1).also {
                                require(it.size <= MAX_PATCH_BYTES) { "The player patch is too large." }
                            }
                        }
                        else -> error("The invitation contains an unsupported entry: ${entry.name}")
                    }
                    zip.closeEntry()
                }
            }
            val root = JSONObject((metadata ?: error("The invitation has no metadata.")).toString(Charsets.UTF_8))
            require(root.optString("format") == FORMAT && root.optInt("version") == PLAYER_PATCH_VERSION) {
                "This is not a supported player-specific invitation."
            }
            val roomId = root.getString("room_id")
            validateRoomId(roomId)
            val playerSlot = root.getInt("player_slot")
            require(playerSlot > 0) { "The invitation contains an invalid player slot." }
            val playerName = root.getString("player_name").trim()
            require(playerName.isNotBlank() && playerName.length <= 256) { "The invitation contains an invalid player name." }
            val patchName = File(root.getString("patch_name")).name
            val patchBytes = patch ?: error("The invitation has no player patch.")
            require(patchBytes.isNotEmpty()) { "The invitation contains an empty player patch." }
            require(root.optString("patch_entry") == PATCH_ENTRY) { "The invitation patch entry is invalid." }
            require(patchBytes.sha256().equals(root.getString("patch_sha256"), ignoreCase = true)) {
                "The player patch failed its integrity check."
            }
            val gameName = patchGame(patchBytes)
            root.optString("patch_game").takeIf { it.isNotBlank() && it != "null" }?.let { declaredGame ->
                require(declaredGame == gameName) { "The invitation's patch game metadata does not match its patch." }
            }
            return RoomInvite(
                roomId = roomId,
                seedId = root.optString("seed_id"),
                playerSlot = playerSlot,
                playerName = playerName,
                patchName = patchName,
                patchBytes = patchBytes,
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
    val gameName: String = "Metroid Fusion",
    val patchedRomSha256: String? = null,
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
        } ?: "Metroid Fusion"
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
        )
        rooms.removeAll { it.roomId == joined.roomId }
        rooms += joined
        persist(context, rooms, joined.roomId)
        return joined
    }

    @Synchronized
    fun rememberPatchedRom(context: Context, name: String, uri: Uri, sha256: String): JoinedRoom? {
        require(SHA256_PATTERN.matches(sha256)) { "Invalid patched ROM fingerprint." }
        val current = load(context) ?: return null
        val updated = current.copy(
            patchedRomName = File(name).name,
            patchedRomUri = uri.toString(),
            patchedRomSha256 = sha256.lowercase(),
        )
        val rooms = loadAll(context).filterNot { it.roomId == updated.roomId } + updated
        persist(context, rooms, updated.roomId)
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
        data.optString("gameName", "Metroid Fusion")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: "Metroid Fusion",
        data.optString("patchedRomSha256")
            .takeIf { SHA256_PATTERN.matches(it) }
            ?.lowercase(),
    )

    private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
}

/** Reads the standard AP patch manifest without requiring game-specific code. */
private fun patchGame(patch: ByteArray): String {
    var legacyMetroidFusion = false
    ZipInputStream(ByteArrayInputStream(patch)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(!entry.isDirectory && '/' !in entry.name && '\\' !in entry.name) {
                "The player patch contains an invalid entry."
            }
            when (entry.name) {
                "patch_file.json" -> legacyMetroidFusion = true
                "archipelago.json" -> {
                    val manifestBytes = zip.readNBytes(MAX_PATCH_MANIFEST_BYTES + 1)
                    require(manifestBytes.size <= MAX_PATCH_MANIFEST_BYTES) { "The patch manifest is too large." }
                    val game = JSONObject(manifestBytes.toString(Charsets.UTF_8)).optString("game").trim()
                    require(game.isNotBlank()) { "The Archipelago patch does not declare a game." }
                    return game
                }
            }
            zip.closeEntry()
        }
    }
    if (legacyMetroidFusion) return "Metroid Fusion"
    error("The selected file is not a supported Archipelago player patch.")
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
}

/** Restricts outgoing invitation URIs to the configured cache subdirectory. */
class InviteFileProvider : FileProvider()
