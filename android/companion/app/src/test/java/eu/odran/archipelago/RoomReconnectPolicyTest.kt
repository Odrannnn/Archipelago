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

    @Test
    fun `transient failures back off exponentially and cap at one minute`() {
        val backoff = RoomReconnectBackoff(randomValue = { 0.5 })

        assertRetryDelay(backoff, 5_000)
        assertRetryDelay(backoff, 10_000)
        assertRetryDelay(backoff, 20_000)
        assertRetryDelay(backoff, 40_000)
        assertRetryDelay(backoff, 60_000)
        assertRetryDelay(backoff, 60_000)
    }

    @Test
    fun `stable room connection resets transient failure backoff`() {
        val backoff = RoomReconnectBackoff(randomValue = { 0.5 })
        assertRetryDelay(backoff, 5_000)
        assertRetryDelay(backoff, 10_000)

        backoff.observeConnected(1_000)
        backoff.observeConnected(31_000)

        assertRetryDelay(backoff, 5_000)
    }

    private fun assertRetryDelay(backoff: RoomReconnectBackoff, expected: Long) {
        val now = 100_000L
        val next = backoff.nextAttemptAfterFailure(now)
        org.junit.Assert.assertEquals(expected, next - now)
    }
}
