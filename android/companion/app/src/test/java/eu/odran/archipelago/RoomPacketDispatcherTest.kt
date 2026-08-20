package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RoomPacketDispatcherTest {
    @Test
    fun `contains game packet handler failures`() {
        val failure = IllegalStateException("emulator unavailable")
        var packetCount = 0

        val result = RoomPacketDispatcher.guard {
            packetCount++
            throw failure
        }

        assertSame(failure, result)
        assertEquals(1, packetCount)
    }

    @Test
    fun `returns no failure after successful packet handling`() {
        var packetCount = 0

        assertNull(RoomPacketDispatcher.guard {
            packetCount++
        })
        assertEquals(1, packetCount)
    }
}
