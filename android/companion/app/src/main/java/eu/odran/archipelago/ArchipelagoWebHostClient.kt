package eu.odran.archipelago

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class HostedRoom(
    val roomId: String,
    val seedId: String,
    val creationTime: String,
    val lastActivity: String,
    val lastPort: Int,
    val timeoutSeconds: Int,
    val trackerId: String,
    val players: List<String>,
)

data class HostSeedResult(
    val room: HostedRoom,
    val rooms: List<HostedRoom>,
)

/** Uploads locally generated seed packages using one persistent archipelago.gg website session. */
class ArchipelagoWebHostClient(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    val websiteSessionId: String
        get() = preferences.getString(SESSION_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(SESSION_ID, it).apply()
        }

    fun hostSeed(seedZip: File): HostSeedResult {
        require(seedZip.isFile) { "The generated seed ZIP is no longer available." }
        require(seedZip.extension.equals("zip", ignoreCase = true)) { "Only generated seed ZIPs can be hosted." }
        ensureWebsiteSession()

        val upload = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                seedZip.name,
                seedZip.asRequestBody("application/zip".toMediaType()),
            )
            .build()
        val seedId = execute(
            Request.Builder().url("$BASE_URL/uploads").post(upload).build(),
        ).use { response ->
            if (response.code !in 300..399) {
                val body = response.body?.string().orEmpty()
                error(extractWebsiteError(body) ?: "archipelago.gg rejected the seed ZIP (HTTP ${response.code}).")
            }
            extractRedirectId(response.header("Location"), "seed")
                ?: error("archipelago.gg uploaded the ZIP but did not return a seed identifier.")
        }

        val roomId = execute(
            Request.Builder().url("$BASE_URL/new_room/$seedId").get().build(),
        ).use { response ->
            if (response.code !in 300..399) {
                error("The seed was uploaded, but archipelago.gg could not create its room (HTTP ${response.code}).")
            }
            extractRedirectId(response.header("Location"), "room")
                ?: error("archipelago.gg created a room but did not return its identifier.")
        }

        // Visiting the room marks it active and asks the website to start its server process.
        execute(Request.Builder().url("$BASE_URL/room/$roomId?update=1").get().build()).use { response ->
            if (!response.isSuccessful) {
                error("Room $roomId was created but could not be started (HTTP ${response.code}).")
            }
        }

        val rooms = runCatching { refreshRooms() }.getOrElse {
            val created = HostedRoom(roomId, seedId, "", "", 0, 0, "", emptyList())
            saveCachedRooms((listOf(created) + cachedRooms()).distinctBy { it.roomId })
            return HostSeedResult(created, cachedRooms())
        }
        val room = rooms.firstOrNull { it.roomId == roomId }
            ?: HostedRoom(roomId, seedId, "", "", 0, 0, "", emptyList())
        return HostSeedResult(room, rooms)
    }

    fun refreshRooms(): List<HostedRoom> {
        ensureWebsiteSession()
        val seeds = getJsonArray("/api/get_seeds")
        val playersBySeed = buildMap {
            repeat(seeds.length()) { index ->
                val seed = seeds.getJSONObject(index)
                put(seed.getString("seed_id"), parsePlayers(seed.optJSONArray("players")))
            }
        }
        val roomData = getJsonArray("/api/get_rooms")
        val rooms = List(roomData.length()) { index ->
            val room = roomData.getJSONObject(index)
            val seedId = room.getString("seed_id")
            HostedRoom(
                roomId = room.getString("room_id"),
                seedId = seedId,
                creationTime = room.optString("creation_time"),
                lastActivity = room.optString("last_activity"),
                lastPort = room.optInt("last_port", 0),
                timeoutSeconds = room.optInt("timeout", 0),
                trackerId = room.optString("tracker"),
                players = playersBySeed[seedId].orEmpty(),
            )
        }
        saveCachedRooms(rooms)
        return rooms
    }

    fun cachedRooms(): List<HostedRoom> = runCatching {
        parseRooms(JSONArray(preferences.getString(ROOM_CACHE, "[]")))
    }.getOrDefault(emptyList())

    /** Resolves a public room invite and wakes a sleeping website-hosted server. */
    fun resolvePublicRoom(roomId: String): HostedRoom {
        require(ROOM_ID_PATTERN.matches(roomId)) { "The invite contains an invalid room identifier." }
        execute(Request.Builder().url("$BASE_URL/room/$roomId?update=1").get().build()).use { response ->
            if (!response.isSuccessful) error("The shared room does not exist (HTTP ${response.code}).")
        }

        var resolved: HostedRoom? = null
        for (delaySeconds in listOf(0L, 2L, 4L, 8L)) {
            if (delaySeconds > 0) Thread.sleep(TimeUnit.SECONDS.toMillis(delaySeconds))
            val status = execute(
                Request.Builder().url("$BASE_URL/api/room_status/$roomId").get().build(),
            ).use { response ->
                if (!response.isSuccessful) error("Could not read the shared room (HTTP ${response.code}).")
                JSONObject(response.body?.string() ?: error("The shared room returned an empty response."))
            }
            resolved = HostedRoom(
                roomId = roomId,
                seedId = "",
                creationTime = "",
                lastActivity = status.optString("last_activity"),
                lastPort = status.optInt("last_port", 0),
                timeoutSeconds = status.optInt("timeout", 0),
                trackerId = status.optString("tracker"),
                players = parsePlayers(status.optJSONArray("players")),
            )
            if (resolved.lastPort != 0) break
        }
        return resolved ?: error("Could not resolve the shared room.")
    }

    fun sessionSyncUrl(): String = "$BASE_URL/session/$websiteSessionId"

    /** Returns the established website cookie for the app's private authenticated WebView. */
    fun authenticatedBrowserCookie(): String {
        ensureWebsiteSession()
        return checkNotNull(preferences.getString(SESSION_COOKIE, null)).also {
            check(it.isNotBlank()) { "archipelago.gg did not return a website session cookie." }
        }
    }

    private fun ensureWebsiteSession() {
        execute(Request.Builder().url(sessionSyncUrl()).get().build()).use { response ->
            if (!response.isSuccessful) {
                error("Could not establish the archipelago.gg website session (HTTP ${response.code}).")
            }
        }
        check(!preferences.getString(SESSION_COOKIE, null).isNullOrBlank()) {
            "archipelago.gg did not return a website session cookie."
        }
    }

    private fun getJsonArray(path: String): JSONArray = execute(
        Request.Builder().url("$BASE_URL$path").get().build(),
    ).use { response ->
        if (!response.isSuccessful) error("archipelago.gg returned HTTP ${response.code} for $path.")
        JSONArray(response.body?.string() ?: error("archipelago.gg returned an empty response for $path."))
    }

    private fun execute(request: Request): okhttp3.Response {
        val authenticated = request.newBuilder().apply {
            preferences.getString(SESSION_COOKIE, null)?.let { header("Cookie", "session=$it") }
            header("User-Agent", "Archipelago-Android-Companion/0.9")
        }.build()
        val response = client.newCall(authenticated).execute()
        response.headers("Set-Cookie").forEach { header ->
            SESSION_COOKIE_PATTERN.find(header)?.groupValues?.getOrNull(1)?.let { value ->
                preferences.edit().putString(SESSION_COOKIE, value).apply()
            }
        }
        return response
    }

    private fun saveCachedRooms(rooms: List<HostedRoom>) {
        val data = JSONArray().apply {
            rooms.forEach { room ->
                put(JSONObject().apply {
                    put("roomId", room.roomId)
                    put("seedId", room.seedId)
                    put("creationTime", room.creationTime)
                    put("lastActivity", room.lastActivity)
                    put("lastPort", room.lastPort)
                    put("timeoutSeconds", room.timeoutSeconds)
                    put("trackerId", room.trackerId)
                    put("players", JSONArray(room.players))
                })
            }
        }
        preferences.edit().putString(ROOM_CACHE, data.toString()).apply()
    }

    private fun parseRooms(data: JSONArray) = List(data.length()) { index ->
        val room = data.getJSONObject(index)
        HostedRoom(
            room.getString("roomId"),
            room.getString("seedId"),
            room.optString("creationTime"),
            room.optString("lastActivity"),
            room.optInt("lastPort", 0),
            room.optInt("timeoutSeconds", 0),
            room.optString("trackerId"),
            room.optJSONArray("players")?.let { players ->
                List(players.length()) { players.getString(it) }
            }.orEmpty(),
        )
    }

    private fun parsePlayers(players: JSONArray?): List<String> {
        if (players == null) return emptyList()
        return List(players.length()) { index ->
            when (val player = players.get(index)) {
                is JSONArray -> {
                    val name = player.optString(0, "Player ${index + 1}")
                    val game = player.optString(1)
                    if (game.isBlank()) name else "$name ($game)"
                }
                else -> player.toString()
            }
        }
    }

    private fun extractRedirectId(location: String?, resource: String): String? {
        if (location == null) return null
        return Regex("(?:^|/)${Regex.escape(resource)}/([A-Za-z0-9_-]+)")
            .find(location)?.groupValues?.getOrNull(1)
    }

    private fun extractWebsiteError(body: String): String? {
        val message = Regex(
            "<div[^>]*class=[\\\"'][^\\\"']*user-message[^\\\"']*[\\\"'][^>]*>(.*?)</div>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(body)?.groupValues?.getOrNull(1) ?: return null
        return message
            .replace(Regex("<[^>]+>"), " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        const val BASE_URL = "https://archipelago.gg"
        private const val PREFERENCES = "archipelago_web_host"
        private const val SESSION_ID = "website_session_id"
        private const val SESSION_COOKIE = "website_session_cookie"
        private const val ROOM_CACHE = "hosted_room_cache"
        private val SESSION_COOKIE_PATTERN = Regex("(?:^|;\\s*)session=([^;]+)")
        val ROOM_ID_PATTERN = Regex("[A-Za-z0-9_-]{16,64}")
    }
}
