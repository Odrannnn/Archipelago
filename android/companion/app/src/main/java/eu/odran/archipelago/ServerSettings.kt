package eu.odran.archipelago

import android.content.Context
import java.net.URI
import java.util.UUID

data class ServerSettings(
    val address: String,
    val password: String,
    val clientId: String,
) {
    val isConfigured: Boolean get() = address.isNotBlank()

    companion object {
        private const val PREFS = "archipelago_server"
        private const val ADDRESS = "address"
        private const val PASSWORD = "password"
        private const val CLIENT_ID = "client_id"

        fun load(context: Context): ServerSettings {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var clientId = preferences.getString(CLIENT_ID, null)
            if (clientId.isNullOrBlank()) {
                clientId = UUID.randomUUID().toString()
                preferences.edit().putString(CLIENT_ID, clientId).apply()
            }
            return ServerSettings(
                address = preferences.getString(ADDRESS, "") ?: "",
                password = preferences.getString(PASSWORD, "") ?: "",
                clientId = clientId,
            )
        }

        fun save(context: Context, address: String, password: String) {
            val withScheme = address.trim().let {
                when {
                    it.startsWith("archipelago://", ignoreCase = true) -> "ws://${it.substringAfter("://")}"
                    it.startsWith("http://", ignoreCase = true) -> "ws://${it.substringAfter("://")}"
                    it.startsWith("https://", ignoreCase = true) -> "wss://${it.substringAfter("://")}"
                    "://" !in it && it.isNotBlank() -> "ws://$it"
                    else -> it
                }
            }
            val normalized = try {
                val uri = URI(withScheme)
                val pathPort = uri.path
                    ?.trim('/')
                    ?.takeIf { candidate -> candidate.all(Char::isDigit) }
                    ?.toIntOrNull()
                    ?.takeIf { candidate -> candidate in 1..65535 }
                when {
                    uri.host != null && uri.port == -1 && pathPort != null ->
                        URI(uri.scheme, uri.userInfo, uri.host, pathPort, null, null, null).toString()
                    uri.host != null && uri.port == -1 ->
                        URI(uri.scheme, uri.userInfo, uri.host, 38281, uri.path, uri.query, uri.fragment).toString()
                    else -> withScheme
                }
            } catch (_: Exception) {
                withScheme
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ADDRESS, normalized)
                .putString(PASSWORD, password)
                .apply()
        }
    }
}
