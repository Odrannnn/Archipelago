package eu.odran.archipelago

data class DolphinTelemetry(
    val connected: Boolean,
    val logicallyHooked: Boolean,
    val intervalNanos: Long,
    val intervalReadRequests: Long,
    val intervalWriteRequests: Long,
    val intervalProbeRequests: Long,
    val intervalBytesRead: Long,
    val intervalBytesWritten: Long,
    val intervalWaitNanos: Long,
    val intervalMaxWaitNanos: Long,
    val intervalFailures: Long,
    val sessionNanos: Long,
    val sessionReadRequests: Long,
    val sessionWriteRequests: Long,
    val sessionProbeRequests: Long,
    val sessionBytesRead: Long,
    val sessionBytesWritten: Long,
    val sessionWaitNanos: Long,
    val sessionMaxWaitNanos: Long,
    val sessionFailures: Long,
) {
    val intervalRequests: Long
        get() = intervalReadRequests + intervalWriteRequests + intervalProbeRequests
    val sessionRequests: Long
        get() = sessionReadRequests + sessionWriteRequests + sessionProbeRequests
}
