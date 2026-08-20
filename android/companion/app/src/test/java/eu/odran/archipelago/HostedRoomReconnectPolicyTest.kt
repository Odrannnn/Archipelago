package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HostedRoomReconnectPolicyTest {
    private val room = JoinedRoom(
        roomId = "-j4lNXkFSdOSUD5mVuxqyQ",
        trackerId = "tracker",
        port = 32_935,
        players = listOf("Player1 (Super Metroid)"),
        updatedAt = 1L,
    )

    @Test
    fun `matches only the selected website room and its saved port`() {
        assertEquals(
            room,
            HostedRoomReconnectPolicy.matchingRoom("ws://archipelago.gg:32935", room),
        )
        assertEquals(
            room,
            HostedRoomReconnectPolicy.matchingRoom("ARCHIPELAGO.GG:32935", room),
        )
        assertNull(HostedRoomReconnectPolicy.matchingRoom("ws://archipelago.gg:38281", room))
        assertNull(HostedRoomReconnectPolicy.matchingRoom("ws://example.com:32935", room))
        assertNull(HostedRoomReconnectPolicy.matchingRoom("not a server", room))
        assertNull(HostedRoomReconnectPolicy.matchingRoom("ws://archipelago.gg:32935", null))
    }

    @Test
    fun `wake attempts are limited to once per minute and recover from clock rollback`() {
        assertTrue(HostedRoomReconnectPolicy.mayWake(now = 100_000L, lastAttemptAt = 0L))
        assertFalse(HostedRoomReconnectPolicy.mayWake(now = 159_999L, lastAttemptAt = 100_000L))
        assertTrue(HostedRoomReconnectPolicy.mayWake(now = 160_000L, lastAttemptAt = 100_000L))
        assertTrue(HostedRoomReconnectPolicy.mayWake(now = 90_000L, lastAttemptAt = 100_000L))
    }

    @Test
    fun `refreshed website room address uses the new port`() {
        assertEquals("archipelago.gg:41000", HostedRoomReconnectPolicy.serverAddress(41_000))
    }
}
