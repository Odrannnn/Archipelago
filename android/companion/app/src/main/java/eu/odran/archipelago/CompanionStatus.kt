package eu.odran.archipelago

/** Semantic state used by status text, chips, and room cards throughout the app. */
enum class CompanionStatusLevel {
    NEUTRAL,
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

enum class RoomRuntimeState {
    RUNNING,
    REFRESHING,
    WAKING,
    SLEEPING,
    ERROR,
    UNAVAILABLE,
}

data class RoomStatusPresentation(
    val state: RoomRuntimeState,
    val label: String,
    val level: CompanionStatusLevel,
    val summary: String,
)

internal fun roomStatusPresentation(
    port: Int,
    refreshing: Boolean = false,
    waking: Boolean = false,
    available: Boolean = true,
): RoomStatusPresentation = when {
    !available -> RoomStatusPresentation(
        RoomRuntimeState.UNAVAILABLE,
        "UNAVAILABLE",
        CompanionStatusLevel.ERROR,
        "The room could not be reached. Refresh it or open its website controls.",
    )
    waking -> RoomStatusPresentation(
        RoomRuntimeState.WAKING,
        "WAKING",
        CompanionStatusLevel.INFO,
        "archipelago.gg is starting the room server and assigning a port.",
    )
    refreshing -> RoomStatusPresentation(
        RoomRuntimeState.REFRESHING,
        "REFRESHING",
        CompanionStatusLevel.INFO,
        "Checking the room's current server state and port.",
    )
    port > 0 -> RoomStatusPresentation(
        RoomRuntimeState.RUNNING,
        "RUNNING",
        CompanionStatusLevel.SUCCESS,
        "The room server is available on port $port.",
    )
    port < 0 -> RoomStatusPresentation(
        RoomRuntimeState.ERROR,
        "ERROR",
        CompanionStatusLevel.ERROR,
        "archipelago.gg reported a room server error.",
    )
    else -> RoomStatusPresentation(
        RoomRuntimeState.SLEEPING,
        "SLEEPING",
        CompanionStatusLevel.WARNING,
        "The room has no active server port. Refreshing it will attempt to wake it.",
    )
}

/**
 * Classifies existing status messages so older call sites receive consistent colours while
 * important flows can still provide an explicit semantic level.
 */
internal fun classifyStatusMessage(message: String): CompanionStatusLevel {
    val normalized = message.trim().lowercase()
    if (normalized.isBlank()) return CompanionStatusLevel.NEUTRAL

    return when {
        listOf(
            "could not", "failed", "failure", "error", "rejected", "invalid",
            "does not exist", "not found", "not loaded", "refused",
        ).any(normalized::contains) -> CompanionStatusLevel.ERROR
        listOf(
            "starting", "checking", "refreshing", "connecting", "loading", "preparing",
            "generating", "reading", "uploading", "creating", "applying", "validating",
            "launching", "opening", "syncing", "waking",
        ).any(normalized::contains) -> CompanionStatusLevel.INFO
        listOf(
            "waiting", "sleeping", "not connected", "disconnected", "unavailable",
            "until refresh", "canceled", "cancelled", "needs ", "no room", "no player",
            "not available", "still starting", "stopped",
        ).any(normalized::contains) -> CompanionStatusLevel.WARNING
        listOf(
            "connected", "ready", "complete", "saved", "remembered", "loaded",
            "up to date", "found ", "updated", "is now active", "created and hosted",
            "opened ", "imported", "applied", "selected ", "sent ", "forgot ",
        ).any(normalized::contains) -> CompanionStatusLevel.SUCCESS
        else -> CompanionStatusLevel.NEUTRAL
    }
}

internal fun formatStatusAge(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0L) return "Not checked yet"
    val elapsedSeconds = ((now - timestamp).coerceAtLeast(0L) / 1_000L)
    return when {
        elapsedSeconds < 10L -> "Checked just now"
        elapsedSeconds < 60L -> "Checked ${elapsedSeconds}s ago"
        elapsedSeconds < 3_600L -> "Checked ${elapsedSeconds / 60L}m ago"
        elapsedSeconds < 86_400L -> "Checked ${elapsedSeconds / 3_600L}h ago"
        else -> "Checked ${elapsedSeconds / 86_400L}d ago"
    }
}
