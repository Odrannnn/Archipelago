package eu.odran.archipelago

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Blocking client for Dolphin's built-in GDB Remote Serial Protocol server.
 *
 * Dolphin exposes guest PowerPC addresses directly through the standard `m` and
 * `M` packets. One instance must own the connection for the complete emulation
 * session because Dolphin accepts only one debugger and does not reopen the
 * listener after a debugger disconnects.
 */
class DolphinGdbClient(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val ioTimeoutMillis: Int = DEFAULT_IO_TIMEOUT_MILLIS,
) : Closeable {
    private val lock = ReentrantLock()
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var logicallyHooked = false

    init {
        require(port in 1..65535) { "Invalid Dolphin GDB port: $port" }
        require(connectTimeoutMillis > 0) { "Connect timeout must be positive" }
        require(ioTimeoutMillis > 0) { "I/O timeout must be positive" }
    }

    /** Connects to Dolphin, consumes its initial stop state, and resumes emulation. */
    fun connect() = lock.withLock {
        if (isSocketOpen()) {
            logicallyHooked = true
            probeLocked()
            return@withLock
        }
        closeLocked()
        val candidate = Socket()
        try {
            candidate.connect(InetSocketAddress(InetAddress.getByName(host), port), connectTimeoutMillis)
            candidate.tcpNoDelay = true
            candidate.soTimeout = ioTimeoutMillis
        } catch (error: IOException) {
            runCatching { candidate.close() }
            throw IOException("Could not connect to Dolphin GDB at $host:$port", error)
        }
        socket = candidate
        input = BufferedInputStream(candidate.getInputStream())
        output = BufferedOutputStream(candidate.getOutputStream())
        try {
            val stop = transactLocked("?")
            check(stop.startsWith("T") || stop.startsWith("S")) {
                "Dolphin GDB returned an invalid initial state: $stop"
            }
            sendWithoutReplyLocked("c")
            logicallyHooked = true
            probeLocked()
        } catch (error: Exception) {
            closeLocked()
            throw error
        }
    }

    /** Memory Engine-compatible hook which reuses the single persistent socket. */
    fun hook() = lock.withLock {
        if (!isSocketOpen()) {
            connect()
        } else {
            logicallyHooked = true
            probeLocked()
        }
    }

    /**
     * Memory Engine-compatible logical detach. Closing the TCP socket here would
     * permanently disable Dolphin's GDB listener until the game is restarted.
     */
    fun unHook() = lock.withLock {
        logicallyHooked = false
    }

    fun isHooked(): Boolean = lock.withLock {
        if (!logicallyHooked || !isSocketOpen()) return@withLock false
        runCatching { probeLocked() }.onFailure { closeLocked() }.isSuccess
    }

    fun assertHooked() {
        if (!isHooked()) throw IllegalStateException("not hooked")
    }

    fun readBytes(consoleAddress: Long, size: Int): ByteArray = lock.withLock {
        requireHookedLocked()
        readBytesLocked(consoleAddress, size)
    }

    fun writeBytes(consoleAddress: Long, data: ByteArray) = lock.withLock {
        requireHookedLocked()
        validateRange(consoleAddress, data.size)
        var offset = 0
        try {
            while (offset < data.size) {
                val length = minOf(MAX_TRANSFER_BYTES, data.size - offset)
                val address = consoleAddress + offset
                val encoded = encodeHex(data, offset, length)
                val response = transactLocked("M${address.toString(16)},${length.toString(16)}:$encoded")
                check(response == "OK") { "Dolphin GDB memory write failed: $response" }
                offset += length
            }
        } catch (error: IOException) {
            closeLocked()
            throw IOException("Could not write Dolphin memory at 0x${consoleAddress.toString(16)}", error)
        }
    }

    fun readByte(consoleAddress: Long): Int = readBytes(consoleAddress, 1)[0].toInt() and 0xFF

    fun writeByte(consoleAddress: Long, value: Int) {
        require(value in 0..0xFF) { "Byte value out of range: $value" }
        writeBytes(consoleAddress, byteArrayOf(value.toByte()))
    }

    /** Six-byte GameCube/Wii disc ID, or an empty string before a title is ready. */
    fun gameId(): String = lock.withLock {
        check(isSocketOpen()) { "Dolphin GDB socket is closed" }
        readBytesLocked(GAME_ID_ADDRESS, GAME_ID_LENGTH)
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .toString(StandardCharsets.US_ASCII)
    }

    fun isSocketConnected(): Boolean = lock.withLock { isSocketOpen() }

    override fun close() = lock.withLock { closeLocked() }

    private fun readBytesLocked(consoleAddress: Long, size: Int): ByteArray {
        validateRange(consoleAddress, size)
        if (size == 0) return ByteArray(0)
        val result = ByteArray(size)
        var offset = 0
        try {
            while (offset < size) {
                val length = minOf(MAX_TRANSFER_BYTES, size - offset)
                val address = consoleAddress + offset
                val response = transactLocked("m${address.toString(16)},${length.toString(16)}")
                check(!response.startsWith("E")) { "Dolphin GDB memory read failed: $response" }
                val decoded = decodeHex(response)
                check(decoded.size == length) {
                    "Dolphin GDB returned ${decoded.size} bytes for a $length-byte read"
                }
                decoded.copyInto(result, offset)
                offset += length
            }
            return result
        } catch (error: IOException) {
            closeLocked()
            throw IOException("Could not read Dolphin memory at 0x${consoleAddress.toString(16)}", error)
        }
    }

    private fun probeLocked() {
        val response = transactLocked("m${GAME_ID_ADDRESS.toString(16)},1")
        check(response.length == 2 && !response.startsWith("E")) {
            "Dolphin has no readable emulated memory"
        }
    }

    private fun requireHookedLocked() {
        check(logicallyHooked && isSocketOpen()) { "not hooked" }
    }

    private fun transactLocked(payload: String): String {
        sendFramedLocked(payload)
        expectAckLocked(payload)
        return readReplyLocked()
    }

    private fun sendWithoutReplyLocked(payload: String) {
        sendFramedLocked(payload)
        expectAckLocked(payload)
    }

    private fun sendFramedLocked(payload: String) {
        val bytes = payload.toByteArray(StandardCharsets.US_ASCII)
        val checksum = bytes.fold(0) { value, byte -> (value + (byte.toInt() and 0xFF)) and 0xFF }
        val frame = "\$$payload#${checksum.toString(16).padStart(2, '0')}"
        checkNotNull(output) { "Dolphin GDB socket is closed" }.apply {
            write(frame.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }

    private fun expectAckLocked(payload: String) {
        when (readRequiredByteLocked().toChar()) {
            '+' -> Unit
            '-' -> {
                sendFramedLocked(payload)
                check(readRequiredByteLocked().toChar() == '+') { "Dolphin GDB rejected packet twice" }
            }
            else -> error("Dolphin GDB did not acknowledge the request")
        }
    }

    private fun readReplyLocked(): String {
        var value: Int
        do {
            value = readRequiredByteLocked()
        } while (value.toChar() != '$')

        val payload = ArrayList<Byte>()
        while (true) {
            value = readRequiredByteLocked()
            if (value.toChar() == '#') break
            payload.add(value.toByte())
        }
        val expectedChecksum = "${readRequiredByteLocked().toChar()}${readRequiredByteLocked().toChar()}"
            .toIntOrNull(16) ?: error("Dolphin GDB returned an invalid checksum")
        val actualChecksum = payload.fold(0) { checksum, byte ->
            (checksum + (byte.toInt() and 0xFF)) and 0xFF
        }
        val valid = expectedChecksum == actualChecksum
        checkNotNull(output).apply {
            write(if (valid) '+'.code else '-'.code)
            flush()
        }
        check(valid) { "Dolphin GDB reply checksum mismatch" }
        return payload.toByteArray().toString(StandardCharsets.US_ASCII)
    }

    private fun readRequiredByteLocked(): Int {
        val value = checkNotNull(input) { "Dolphin GDB socket is closed" }.read()
        if (value < 0) throw EOFException("Dolphin GDB closed the connection")
        return value
    }

    private fun isSocketOpen(): Boolean = socket?.let { it.isConnected && !it.isClosed } == true

    private fun closeLocked() {
        logicallyHooked = false
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun validateRange(consoleAddress: Long, size: Int) {
        require(size >= 0) { "Memory size must not be negative" }
        require(consoleAddress in 0..MAX_CONSOLE_ADDRESS) {
            "Console address out of range: 0x${consoleAddress.toString(16)}"
        }
        require(size.toLong() <= MAX_CONSOLE_ADDRESS + 1 - consoleAddress) {
            "Memory range exceeds the 32-bit console address space"
        }
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 55020
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 1_000
        private const val DEFAULT_IO_TIMEOUT_MILLIS = 2_000
        private const val MAX_TRANSFER_BYTES = 1_024
        private const val MAX_CONSOLE_ADDRESS = 0xFFFF_FFFFL
        private const val GAME_ID_ADDRESS = 0x8000_0000L
        private const val GAME_ID_LENGTH = 6

        private val HEX = "0123456789abcdef".toCharArray()

        private fun encodeHex(data: ByteArray, offset: Int, length: Int): String =
            CharArray(length * 2).also { output ->
                repeat(length) { index ->
                    val value = data[offset + index].toInt() and 0xFF
                    output[index * 2] = HEX[value ushr 4]
                    output[index * 2 + 1] = HEX[value and 0x0F]
                }
            }.concatToString()

        private fun decodeHex(value: String): ByteArray {
            require(value.length % 2 == 0) { "Dolphin GDB returned odd-length hexadecimal data" }
            return ByteArray(value.length / 2) { index ->
                val high = value[index * 2].digitToIntOrNull(16)
                    ?: error("Dolphin GDB returned invalid hexadecimal data")
                val low = value[index * 2 + 1].digitToIntOrNull(16)
                    ?: error("Dolphin GDB returned invalid hexadecimal data")
                ((high shl 4) or low).toByte()
            }
        }
    }
}
