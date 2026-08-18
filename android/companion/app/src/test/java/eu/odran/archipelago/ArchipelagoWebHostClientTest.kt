package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchipelagoWebHostClientTest {
    @Test
    fun hiddenHostedRoomsStayOutOfRefreshedResults() {
        val visible = room("visible-room-1234")
        val removed = room("removed-room-1234")

        assertEquals(
            listOf(visible),
            visibleHostedRooms(listOf(visible, removed), setOf(removed.roomId)),
        )
    }

    @Test
    fun clearingHiddenRoomsRestoresAllResults() {
        val rooms = listOf(room("first-room-123456"), room("second-room-12345"))

        assertEquals(rooms, visibleHostedRooms(rooms, emptySet()))
    }

    private fun room(id: String) = HostedRoom(
        roomId = id,
        seedId = "seed-$id",
        creationTime = "",
        lastActivity = "",
        lastPort = 0,
        timeoutSeconds = 0,
        trackerId = "",
        players = emptyList(),
    )
}
