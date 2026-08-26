package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveRoomHealthPolicyTest {
    @Test
    fun `healthy rooms use a restrained thirty minute interval`() {
        assertEquals(30L * 60L * 1_000L, roomHealthNextDelayMillis(RoomHealthOutcome.HEALTHY))
    }

    @Test
    fun `sleeping rooms are checked only every six hours`() {
        assertEquals(6L * 60L * 60L * 1_000L, roomHealthNextDelayMillis(RoomHealthOutcome.SLEEPING))
    }

    @Test
    fun `failures back off exponentially and are capped`() {
        assertEquals(30L * 60L * 1_000L, roomHealthNextDelayMillis(RoomHealthOutcome.FAILURE, 1))
        assertEquals(60L * 60L * 1_000L, roomHealthNextDelayMillis(RoomHealthOutcome.FAILURE, 2))
        assertEquals(2L * 60L * 60L * 1_000L, roomHealthNextDelayMillis(RoomHealthOutcome.FAILURE, 3))
        assertEquals(6L * 60L * 60L * 1_000L, roomHealthNextDelayMillis(RoomHealthOutcome.FAILURE, 20))
    }
}
