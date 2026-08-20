package eu.odran.archipelago

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomReconnectPolicyTest {
    @Test
    fun `does not reconnect a remembered room while its emulator is absent`() {
        assertFalse(
            RoomReconnectPolicy.mayStart(
                serverPaused = false,
                settingsConfigured = true,
                sessionPresent = false,
                emulatorAvailable = false,
                now = 2_000,
                nextAttempt = 1_000,
            ),
        )
    }

    @Test
    fun `reconnects once the emulator returns and retry deadline has passed`() {
        assertTrue(
            RoomReconnectPolicy.mayStart(
                serverPaused = false,
                settingsConfigured = true,
                sessionPresent = false,
                emulatorAvailable = true,
                now = 2_000,
                nextAttempt = 1_000,
            ),
        )
    }
}
