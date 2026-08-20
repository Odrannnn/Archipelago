package eu.odran.archipelago

import kotlin.math.min
import kotlin.random.Random

/** Prevents a remembered game client from reconnecting its room without a live emulator. */
internal object RoomReconnectPolicy {
    fun mayStart(
        serverPaused: Boolean,
        settingsConfigured: Boolean,
        sessionPresent: Boolean,
        emulatorAvailable: Boolean,
        now: Long,
        nextAttempt: Long,
    ): Boolean =
        !serverPaused &&
            settingsConfigured &&
            !sessionPresent &&
            emulatorAvailable &&
            now >= nextAttempt
}

/** Shared bounded exponential backoff for transient room transport failures. */
internal class RoomReconnectBackoff(
    private val initialDelayMillis: Long = 5_000L,
    private val maximumDelayMillis: Long = 60_000L,
    private val stableConnectionMillis: Long = 30_000L,
    private val jitterFraction: Double = 0.20,
    private val randomValue: () -> Double = Random.Default::nextDouble,
) {
    private var consecutiveFailures = 0
    private var connectedSince: Long? = null

    init {
        require(initialDelayMillis > 0)
        require(maximumDelayMillis >= initialDelayMillis)
        require(stableConnectionMillis >= 0)
        require(jitterFraction in 0.0..1.0)
    }

    fun reset() {
        consecutiveFailures = 0
        connectedSince = null
    }

    fun observeConnected(now: Long) {
        val since = connectedSince
        if (since == null) {
            connectedSince = now
        } else if (now - since >= stableConnectionMillis) {
            consecutiveFailures = 0
        }
    }

    fun nextAttemptAfterFailure(now: Long): Long {
        connectedSince = null
        val shift = min(consecutiveFailures, 30)
        val multiplier = 1L shl shift
        val baseDelay = if (initialDelayMillis > maximumDelayMillis / multiplier) {
            maximumDelayMillis
        } else {
            min(initialDelayMillis * multiplier, maximumDelayMillis)
        }
        if (consecutiveFailures < 30) consecutiveFailures++

        val spread = (baseDelay * jitterFraction).toLong()
        val centeredRandom = randomValue().coerceIn(0.0, 1.0) * 2.0 - 1.0
        val jitter = (spread * centeredRandom).toLong()
        return now + (baseDelay + jitter).coerceAtLeast(1_000L)
    }
}
