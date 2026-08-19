package eu.odran.archipelago

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

/** Persisted SAF references for disc images which are too large for the byte cache. */
object BaseRomDocumentStore {
    private const val PREFERENCES = "base_rom_documents"

    fun load(context: Context, game: String, inputKey: String): Uri? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(key(game, inputKey), null)?.let(Uri::parse)

    fun store(context: Context, game: String, inputKey: String, uri: Uri) {
        // ACTION_OPEN_DOCUMENT normally provides a persistable grant, but some
        // OEM and third-party file providers only provide a temporary read
        // grant. That must not turn a successfully validated/patched ISO into a
        // ROM validation failure. In that case the user simply chooses it again
        // for the next seed.
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess
        if (!persisted) return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(key(game, inputKey), uri.toString()).apply()
    }

    fun isPresent(context: Context, game: String): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all.keys.any {
            it.startsWith(prefix(game))
        }

    fun forget(context: Context, game: String): Boolean {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val keys = preferences.all.keys.filter { it.startsWith(prefix(game)) }
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        return editor.commit()
    }

    private fun prefix(game: String) = "rom_${hash(game)}_"
    private fun key(game: String, inputKey: String) = "${prefix(game)}${hash(inputKey)}"
    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
}
