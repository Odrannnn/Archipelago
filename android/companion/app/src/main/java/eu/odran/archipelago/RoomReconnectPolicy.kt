package eu.odran.archipelago

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
