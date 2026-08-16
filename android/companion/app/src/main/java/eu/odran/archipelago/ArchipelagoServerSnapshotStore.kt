package eu.odran.archipelago

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Durable, private cache of server-authoritative state for one patched ROM identity. */
class ArchipelagoServerSnapshotStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun load(romInfo: ImportedGbaRomInfo): JSONObject? {
        val encoded = preferences.getString(key(romInfo), null) ?: return null
        return try {
            JSONObject(encoded).takeIf { snapshot ->
                snapshot.optInt("version") == SNAPSHOT_VERSION &&
                    snapshot.optString("game") == romInfo.game &&
                    snapshot.optString("auth") == romInfo.auth &&
                    snapshot.optBoolean("checked_locations_scouted")
            }
        } catch (_: Exception) {
            remove(romInfo)
            null
        }
    }

    fun save(romInfo: ImportedGbaRomInfo, snapshot: JSONObject) {
        snapshot.put("version", SNAPSHOT_VERSION)
        snapshot.put("game", romInfo.game)
        snapshot.put("auth", romInfo.auth)
        preferences.edit().putString(key(romInfo), snapshot.toString()).commit()
    }

    fun remove(romInfo: ImportedGbaRomInfo) {
        preferences.edit().remove(key(romInfo)).commit()
    }

    private fun key(romInfo: ImportedGbaRomInfo): String {
        val identity = "${romInfo.game}\u0000${romInfo.auth}".encodeToByteArray()
        return MessageDigest.getInstance("SHA-256")
            .digest(identity)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    companion object {
        private const val PREFERENCES = "archipelago_server_snapshots"
        private const val SNAPSHOT_VERSION = 2
    }
}
