package gg.archipelago.android

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
            put("patch_entry", PATCH_ENTRY)
            put("patch_sha256", patchBytes?.sha256())
        }
    }.toString(2)

    companion object {
        const val MIME_TYPE = "application/vnd.gg.archipelago.companion-invite"
        const val LEGACY_MIME_TYPE = "application/vnd.gg.archipelago.companion-invite+json"
        const val URI_SCHEME = "archipelago-companion"
        private const val FORMAT = "gg.archipelago.android.room-invite"
        private const val LEGACY_VERSION = 1
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
            require(safePatchName.endsWith(".apmetfus", ignoreCase = true)) {
                "The selected file is not a Metroid Fusion player patch."
            }
            val invite = RoomInvite(
                room.roomId,
                room.seedId,
                playerSlot,
                playerName,
                safePatchName,
                patchBytes,
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
                appendLine("The attached companion invite contains your Metroid Fusion player patch.")
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
            return if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                parsePlayerPackage(bytes)
            } else {
                parseLegacyJson(bytes)
            }
        }

        private fun parseLegacyJson(bytes: ByteArray): RoomInvite {
            require(bytes.size <= MAX_METADATA_BYTES) { "The legacy invitation is too large." }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.optString("format") == FORMAT && root.optInt("version") == LEGACY_VERSION) {
                "This is not a supported Archipelago Companion invitation."
            }
            val roomId = root.getString("room_id")
            validateRoomId(roomId)
            return RoomInvite(roomId, root.optString("seed_id"))
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
            require(patchName.endsWith(".apmetfus", ignoreCase = true)) {
                "The invitation does not contain a Metroid Fusion patch."
            }
            val patchBytes = patch ?: error("The invitation has no player patch.")
            require(patchBytes.isNotEmpty()) { "The invitation contains an empty player patch." }
            require(root.optString("patch_entry") == PATCH_ENTRY) { "The invitation patch entry is invalid." }
            require(patchBytes.sha256().equals(root.getString("patch_sha256"), ignoreCase = true)) {
                "The player patch failed its integrity check."
            }
            return RoomInvite(
                roomId,
                root.optString("seed_id"),
                playerSlot,
                playerName,
                patchName,
                patchBytes,
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
        )
        rooms.removeAll { it.roomId == joined.roomId }
        rooms += joined
        persist(context, rooms, joined.roomId)
        return joined
    }

    @Synchronized
    fun rememberPatchedRom(context: Context, name: String, uri: Uri): JoinedRoom? {
        val current = load(context) ?: return null
        val updated = current.copy(
            patchedRomName = File(name).name,
            patchedRomUri = uri.toString(),
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
    )
}

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
