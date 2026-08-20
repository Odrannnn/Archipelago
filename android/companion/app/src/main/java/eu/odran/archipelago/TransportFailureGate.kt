package eu.odran.archipelago

/** Keeps a live emulator runtime through short transport scheduling gaps. */
internal class TransportFailureGate(
    private val consecutiveFailureLimit: Int,
    private val outageLimitMs: Long,
) {
    private var firstFailureAt: Long? = null

    var consecutiveFailures: Int = 0
        private set

    init {
        require(consecutiveFailureLimit > 0)
        require(outageLimitMs > 0)
    }

    /** Returns true when the transport should finally be detached. */
    fun recordFailure(now: Long): Boolean {
        val startedAt = firstFailureAt ?: now.also { firstFailureAt = it }
        consecutiveFailures++
        return consecutiveFailures >= consecutiveFailureLimit || now - startedAt >= outageLimitMs
    }

    fun reset() {
        consecutiveFailures = 0
        firstFailureAt = null
    }
}
