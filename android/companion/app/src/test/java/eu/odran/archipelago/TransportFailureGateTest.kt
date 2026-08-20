package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportFailureGateTest {
    @Test
    fun detachesOnlyAfterConfiguredConsecutiveFailures() {
        val gate = TransportFailureGate(consecutiveFailureLimit = 3, outageLimitMs = 5_000)

        assertFalse(gate.recordFailure(1_000))
        assertFalse(gate.recordFailure(2_000))
        assertTrue(gate.recordFailure(3_000))
        assertEquals(3, gate.consecutiveFailures)
    }

    @Test
    fun sustainedOutageCanDetachBeforeFailureLimit() {
        val gate = TransportFailureGate(consecutiveFailureLimit = 10, outageLimitMs = 5_000)

        assertFalse(gate.recordFailure(1_000))
        assertTrue(gate.recordFailure(6_000))
    }

    @Test
    fun successfulProbeResetsFailureHistory() {
        val gate = TransportFailureGate(consecutiveFailureLimit = 3, outageLimitMs = 5_000)

        assertFalse(gate.recordFailure(1_000))
        assertFalse(gate.recordFailure(2_000))
        gate.reset()
        assertEquals(0, gate.consecutiveFailures)
        assertFalse(gate.recordFailure(10_000))
        assertFalse(gate.recordFailure(11_000))
    }
}
