package eu.odran.archipelago

import java.util.Locale

internal data class DolphinTelemetryRates(
    val requestsPerSecond: Double,
    val kibibytesPerSecond: Double,
)

internal object DolphinTelemetryFormatter {
    fun rates(snapshot: DolphinTelemetry): DolphinTelemetryRates {
        val seconds = snapshot.intervalNanos.toDouble() / NANOS_PER_SECOND
        val bytes = snapshot.intervalBytesRead + snapshot.intervalBytesWritten
        return DolphinTelemetryRates(
            requestsPerSecond = snapshot.intervalRequests / seconds,
            kibibytesPerSecond = bytes / 1024.0 / seconds,
        )
    }

    fun display(
        snapshot: DolphinTelemetry,
        gameId: String,
        transportLabel: String,
        port: Int,
        peakRequestsPerSecond: Double,
        peakKibibytesPerSecond: Double,
    ): String {
        val rates = rates(snapshot)
        val intervalAverageMillis = averageMillis(snapshot.intervalWaitNanos, snapshot.intervalRequests)
        val sessionAverageMillis = averageMillis(snapshot.sessionWaitNanos, snapshot.sessionRequests)
        return String.format(
            Locale.US,
            "Connected · %s · %s · 127.0.0.1:%d\n" +
                "Live %.1f req/s · %.2f KiB/s · avg %.2f ms · max %.2f ms\n" +
                "Sample reads %d (%s) · writes %d (%s) · probes %d · failures %d\n" +
                "Session %d requests · avg %.2f ms · max %.2f ms · peak %.1f req/s / %.2f KiB/s · failures %d",
            gameId.ifBlank { "unknown game" },
            transportLabel,
            port,
            rates.requestsPerSecond,
            rates.kibibytesPerSecond,
            intervalAverageMillis,
            nanosToMillis(snapshot.intervalMaxWaitNanos),
            snapshot.intervalReadRequests,
            formatBytes(snapshot.intervalBytesRead),
            snapshot.intervalWriteRequests,
            formatBytes(snapshot.intervalBytesWritten),
            snapshot.intervalProbeRequests,
            snapshot.intervalFailures,
            snapshot.sessionRequests,
            sessionAverageMillis,
            nanosToMillis(snapshot.sessionMaxWaitNanos),
            peakRequestsPerSecond,
            peakKibibytesPerSecond,
            snapshot.sessionFailures,
        )
    }

    fun logLine(snapshot: DolphinTelemetry, gameId: String, transportLabel: String, port: Int): String {
        val rates = rates(snapshot)
        return String.format(
            Locale.US,
            "Dolphin telemetry transport=%s game=%s port=%d requests=%d rate=%.1f/s read=%dB write=%dB " +
                "avg=%.2fms max=%.2fms failures=%d session_requests=%d session_failures=%d",
            transportLabel,
            gameId.ifBlank { "unknown" },
            port,
            snapshot.intervalRequests,
            rates.requestsPerSecond,
            snapshot.intervalBytesRead,
            snapshot.intervalBytesWritten,
            averageMillis(snapshot.intervalWaitNanos, snapshot.intervalRequests),
            nanosToMillis(snapshot.intervalMaxWaitNanos),
            snapshot.intervalFailures,
            snapshot.sessionRequests,
            snapshot.sessionFailures,
        )
    }

    private fun averageMillis(waitNanos: Long, requests: Long): Double =
        if (requests == 0L) 0.0 else nanosToMillis(waitNanos) / requests

    private fun nanosToMillis(nanos: Long): Double = nanos / 1_000_000.0

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.2f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private const val NANOS_PER_SECOND = 1_000_000_000.0
}
