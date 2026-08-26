package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionStatusTest {
    @Test
    fun `room ports map to clear semantic states`() {
        assertEquals(RoomRuntimeState.RUNNING, roomStatusPresentation(38_281).state)
        assertEquals(CompanionStatusLevel.SUCCESS, roomStatusPresentation(38_281).level)
        assertEquals(RoomRuntimeState.SLEEPING, roomStatusPresentation(0).state)
        assertEquals(CompanionStatusLevel.WARNING, roomStatusPresentation(0).level)
        assertEquals(RoomRuntimeState.ERROR, roomStatusPresentation(-1).state)
        assertEquals(CompanionStatusLevel.ERROR, roomStatusPresentation(-1).level)
    }

    @Test
    fun `transient room work takes priority over the cached port`() {
        assertEquals(RoomRuntimeState.REFRESHING, roomStatusPresentation(38_281, refreshing = true).state)
        assertEquals(RoomRuntimeState.WAKING, roomStatusPresentation(0, waking = true).state)
        assertEquals(RoomRuntimeState.UNAVAILABLE, roomStatusPresentation(38_281, available = false).state)
    }

    @Test
    fun `common messages receive consistent status levels`() {
        assertEquals(CompanionStatusLevel.SUCCESS, classifyStatusMessage("Archipelago connected"))
        assertEquals(CompanionStatusLevel.INFO, classifyStatusMessage("Refreshing room status and port…"))
        assertEquals(CompanionStatusLevel.WARNING, classifyStatusMessage("Room is sleeping"))
        assertEquals(CompanionStatusLevel.ERROR, classifyStatusMessage("Could not refresh room"))
        assertEquals(CompanionStatusLevel.NEUTRAL, classifyStatusMessage("Rooms cached on this device."))
    }

    @Test
    fun `status age remains concise and handles clock rollback`() {
        val now = 1_000_000L
        assertEquals("Not checked yet", formatStatusAge(0L, now))
        assertEquals("Checked just now", formatStatusAge(now - 9_000L, now))
        assertEquals("Checked 45s ago", formatStatusAge(now - 45_000L, now))
        assertEquals("Checked 5m ago", formatStatusAge(now - 300_000L, now))
        assertEquals("Checked just now", formatStatusAge(now + 10_000L, now))
    }
}
