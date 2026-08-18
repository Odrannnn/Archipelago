package eu.odran.archipelago

import java.io.Closeable

data class SniTransportStatus(
    val description: String,
    val resetGeneration: Long? = null,
)

/** Emulator-memory transport consumed by the source-compatible SNI runtime. */
interface SniMemoryClient : Closeable {
    fun checkStatus(): SniTransportStatus
    fun readSni(address: Long, length: Int): ByteArray
    fun writeSni(address: Long, data: ByteArray)
}
