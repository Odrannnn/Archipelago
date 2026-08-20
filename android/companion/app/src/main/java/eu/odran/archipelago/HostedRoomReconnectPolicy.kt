package eu.odran.archipelago

import java.net.URI

/** Limits automatic wake requests to the website room which supplied the failing server port. */
internal object HostedRoomReconnectPolicy {
    const val MIN_WAKE_INTERVAL_MILLIS = 60_000L

    fun matchingRoom(serverAddress: String, selectedRoom: JoinedRoom?): JoinedRoom? {
        val room = selectedRoom?.takeIf {
            it.port in 1..65535 && ArchipelagoWebHostClient.ROOM_ID_PATTERN.matches(it.roomId)
        } ?: return null
        val endpoint = endpoint(serverAddress) ?: return null
        return room.takeIf {
            endpoint.host.equals("archipelago.gg", ignoreCase = true) && endpoint.port == room.port
        }
    }

    fun mayWake(now: Long, lastAttemptAt: Long): Boolean =
        lastAttemptAt <= 0L || now < lastAttemptAt || now - lastAttemptAt >= MIN_WAKE_INTERVAL_MILLIS

    fun serverAddress(port: Int): String {
        require(port in 1..65535) { "Invalid Archipelago room port" }
        return "archipelago.gg:$port"
    }

    private fun endpoint(address: String): Endpoint? = runCatching {
        val normalized = address.trim().let {
            if ("://" in it) it else "ws://$it"
        }
        val uri = URI(normalized)
        val host = uri.host?.takeIf(String::isNotBlank) ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        Endpoint(host, port)
    }.getOrNull()

    private data class Endpoint(val host: String, val port: Int)
}
