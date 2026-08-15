package gg.archipelago.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RoomInvite(
    val roomId: String,
    val seedId: String,
) {
    fun toJson() = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("room_id", roomId)
        put("seed_id", seedId)
    }.toString(2)

    companion object {
        const val MIME_TYPE = "application/vnd.gg.archipelago.companion-invite+json"
        const val URI_SCHEME = "archipelago-companion"
        private const val FORMAT = "gg.archipelago.android.room-invite"
        private const val VERSION = 1
        private const val MAX_INVITE_BYTES = 64 * 1024

        fun share(context: Context, room: HostedRoom) {
            val invite = RoomInvite(room.roomId, room.seedId)
            val directory = File(context.cacheDir, "shared_invites").apply { mkdirs() }
            directory.listFiles()?.filter { it.isFile && it.name.endsWith(".apinvite") }?.forEach { it.delete() }
            val file = File(directory, "Archipelago-${room.roomId.take(8)}.apinvite")
            file.writeText(invite.toJson())
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

            val roomUrl = "${ArchipelagoWebHostClient.BASE_URL}/room/${room.roomId}"
            val appLink = "$URI_SCHEME://join/${room.roomId}"
            val trackerUrl = room.trackerId.takeIf { it.isNotBlank() }?.let {
                "${ArchipelagoWebHostClient.BASE_URL}/tracker/$it"
            }
            val message = buildString {
                appendLine("Join my Archipelago multiplayer seed!")
                appendLine()
                appendLine("Companion invite: $appLink")
                appendLine("Room and player patches: $roomUrl")
                if (room.lastPort > 0) appendLine("Server: archipelago.gg:${room.lastPort}")
                trackerUrl?.let { appendLine("Tracker: $it") }
                if (room.players.isNotEmpty()) appendLine("Players: ${room.players.joinToString()}")
                append("Open the attached .apinvite file with Archipelago Companion to load the room automatically.")
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
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.optString("format") == FORMAT && root.optInt("version") == VERSION) {
                "This is not a supported Archipelago Companion invitation."
            }
            val roomId = root.getString("room_id")
            validateRoomId(roomId)
            return RoomInvite(roomId, root.optString("seed_id"))
        }

        private fun validateRoomId(roomId: String) {
            require(ArchipelagoWebHostClient.ROOM_ID_PATTERN.matches(roomId)) {
                "The invitation contains an invalid room identifier."
            }
        }
    }
}

data class JoinedRoom(
    val roomId: String,
    val trackerId: String,
    val port: Int,
    val players: List<String>,
    val updatedAt: Long,
)

object JoinedRoomStore {
    private const val PREFERENCES = "joined_archipelago_room"
    private const val ROOM = "room"

    fun save(context: Context, room: HostedRoom): JoinedRoom {
        val joined = JoinedRoom(
            room.roomId,
            room.trackerId,
            room.lastPort,
            room.players,
            System.currentTimeMillis(),
        )
        val data = JSONObject().apply {
            put("roomId", joined.roomId)
            put("trackerId", joined.trackerId)
            put("port", joined.port)
            put("players", JSONArray(joined.players))
            put("updatedAt", joined.updatedAt)
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(ROOM, data.toString()).apply()
        return joined
    }

    fun load(context: Context): JoinedRoom? = runCatching {
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ROOM, null) ?: return null
        val data = JSONObject(raw)
        JoinedRoom(
            data.getString("roomId"),
            data.optString("trackerId"),
            data.optInt("port", 0),
            data.optJSONArray("players")?.let { players ->
                List(players.length()) { players.getString(it) }
            }.orEmpty(),
            data.optLong("updatedAt", 0L),
        )
    }.getOrNull()
}

/** Restricts outgoing invitation URIs to the configured cache subdirectory. */
class InviteFileProvider : FileProvider()
