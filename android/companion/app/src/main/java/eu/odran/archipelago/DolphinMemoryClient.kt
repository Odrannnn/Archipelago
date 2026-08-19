package eu.odran.archipelago

import java.io.Closeable

/** Raw console-memory transport used behind the desktop-compatible DME module. */
interface DolphinMemoryClient : Closeable {
    val port: Int
    val transportLabel: String

    fun connect()
    fun hook()
    fun unHook()
    fun isHooked(): Boolean
    fun assertHooked()
    fun readBytes(consoleAddress: Long, size: Int): ByteArray
    fun writeBytes(consoleAddress: Long, data: ByteArray)
    fun readByte(consoleAddress: Long): Int
    fun writeByte(consoleAddress: Long, value: Int)
    fun gameId(): String
    fun isSocketConnected(): Boolean
    fun takeTelemetrySnapshot(): DolphinTelemetry
}
