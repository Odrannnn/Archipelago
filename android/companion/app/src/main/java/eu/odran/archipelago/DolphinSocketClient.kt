package eu.odran.archipelago

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Client for the loopback raw-memory service built into Dolphin Archipelago.
 *
 * The service transfers unchanged MEM1/MEM2 bytes and accepts reconnects, so
 * this class can implement the same public operations as desktop DME.
 */
class DolphinSocketClient(
    private val host: String = DEFAULT_HOST,
    override val port: Int = DEFAULT_PORT,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val responseTimeoutMillis: Int = DEFAULT_RESPONSE_TIMEOUT_MILLIS,
) : DolphinMemoryClient {
    override val transportLabel = "fast memory"

    private val lock = ReentrantLock()
    @Volatile private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var logicallyHooked = false
    private var nextRequestId = 1
    private var lastStatus: ServerStatus? = null

    private val sessionStartedNanos = System.nanoTime()
    private var intervalStartedNanos = sessionStartedNanos
    private var intervalReadRequests = 0L
    private var intervalWriteRequests = 0L
    private var intervalProbeRequests = 0L
    private var intervalBytesRead = 0L
    private var intervalBytesWritten = 0L
    private var intervalWaitNanos = 0L
    private var intervalMaxWaitNanos = 0L
    private var intervalFailures = 0L
    private var sessionReadRequests = 0L
    private var sessionWriteRequests = 0L
    private var sessionProbeRequests = 0L
    private var sessionBytesRead = 0L
    private var sessionBytesWritten = 0L
    private var sessionWaitNanos = 0L
    private var sessionMaxWaitNanos = 0L
    private var sessionFailures = 0L

    init {
        require(port in 1..65535) { "Invalid Dolphin memory-service port: $port" }
        require(connectTimeoutMillis > 0) { "Connect timeout must be positive" }
        require(responseTimeoutMillis > 0) { "Response timeout must be positive" }
    }

    override fun connect() = lock.withLock {
        if (isSocketOpen()) {
            logicallyHooked = true
            lastStatus = statusLocked()
            return@withLock
        }

        closeLocked()
        val candidate = Socket()
        socket = candidate
        try {
            candidate.connect(InetSocketAddress(InetAddress.getByName(host), port), connectTimeoutMillis)
            candidate.tcpNoDelay = true
            candidate.keepAlive = true
            // Dolphin can stop servicing its loopback socket while Android
            // backgrounds, pauses, or tears down emulation. Every Python game
            // adapter shares one runtime lock, so an unbounded read here would
            // also freeze APWorld validation, generation, and patching.
            candidate.soTimeout = responseTimeoutMillis
            input = DataInputStream(BufferedInputStream(candidate.getInputStream()))
            output = DataOutputStream(BufferedOutputStream(candidate.getOutputStream()))
            lastStatus = statusLocked()
            logicallyHooked = true
        } catch (error: Exception) {
            closeLocked()
            throw IOException("Could not connect to Dolphin fast memory at $host:$port", error)
        }
    }

    override fun hook() = lock.withLock {
        if (!isSocketOpen()) connect() else logicallyHooked = true
    }

    override fun unHook() = lock.withLock {
        logicallyHooked = false
    }

    override fun isHooked(): Boolean = lock.withLock {
        logicallyHooked && isSocketOpen()
    }

    override fun assertHooked() {
        if (!isHooked()) throw IllegalStateException("not hooked")
    }

    override fun readBytes(consoleAddress: Long, size: Int): ByteArray = lock.withLock {
        requireHookedLocked()
        validateRange(consoleAddress, size)
        if (size == 0) return@withLock ByteArray(0)

        val result = ByteArray(size)
        var offset = 0
        try {
            while (offset < size) {
                val length = minOf(MAX_TRANSFER_BYTES, size - offset)
                val request = ByteBuffer.allocate(8)
                    .putInt((consoleAddress + offset).toInt())
                    .putInt(length)
                    .array()
                val response = trackedTransactionLocked(RequestKind.READ, length, OP_READ, request)
                check(response.size == length) {
                    "Dolphin returned ${response.size} bytes for a $length-byte read"
                }
                response.copyInto(result, offset)
                offset += length
            }
            result
        } catch (error: IOException) {
            closeLocked()
            throw IOException("Could not read Dolphin memory at 0x${consoleAddress.toString(16)}", error)
        }
    }

    override fun writeBytes(consoleAddress: Long, data: ByteArray) = lock.withLock {
        requireHookedLocked()
        validateRange(consoleAddress, data.size)
        var offset = 0
        try {
            while (offset < data.size) {
                val length = minOf(MAX_TRANSFER_BYTES, data.size - offset)
                val request = ByteBuffer.allocate(8 + length)
                    .putInt((consoleAddress + offset).toInt())
                    .putInt(length)
                    .put(data, offset, length)
                    .array()
                val response = trackedTransactionLocked(RequestKind.WRITE, length, OP_WRITE, request)
                check(response.isEmpty()) { "Dolphin returned data for a memory write" }
                offset += length
            }
        } catch (error: IOException) {
            closeLocked()
            throw IOException("Could not write Dolphin memory at 0x${consoleAddress.toString(16)}", error)
        }
    }

    override fun readByte(consoleAddress: Long): Int = readBytes(consoleAddress, 1)[0].toInt() and 0xFF

    override fun writeByte(consoleAddress: Long, value: Int) {
        require(value in 0..0xFF) { "Byte value out of range: $value" }
        writeBytes(consoleAddress, byteArrayOf(value.toByte()))
    }

    override fun gameId(): String = lock.withLock {
        check(isSocketOpen()) { "Dolphin fast-memory socket is closed" }
        statusLocked().also { lastStatus = it }.gameId
    }

    override fun isSocketConnected(): Boolean = lock.withLock { isSocketOpen() }

    override fun takeTelemetrySnapshot(): DolphinTelemetry = lock.withLock {
        val now = System.nanoTime()
        DolphinTelemetry(
            connected = isSocketOpen(),
            logicallyHooked = logicallyHooked,
            intervalNanos = (now - intervalStartedNanos).coerceAtLeast(1L),
            intervalReadRequests = intervalReadRequests,
            intervalWriteRequests = intervalWriteRequests,
            intervalProbeRequests = intervalProbeRequests,
            intervalBytesRead = intervalBytesRead,
            intervalBytesWritten = intervalBytesWritten,
            intervalWaitNanos = intervalWaitNanos,
            intervalMaxWaitNanos = intervalMaxWaitNanos,
            intervalFailures = intervalFailures,
            sessionNanos = (now - sessionStartedNanos).coerceAtLeast(1L),
            sessionReadRequests = sessionReadRequests,
            sessionWriteRequests = sessionWriteRequests,
            sessionProbeRequests = sessionProbeRequests,
            sessionBytesRead = sessionBytesRead,
            sessionBytesWritten = sessionBytesWritten,
            sessionWaitNanos = sessionWaitNanos,
            sessionMaxWaitNanos = sessionMaxWaitNanos,
            sessionFailures = sessionFailures,
        ).also {
            intervalStartedNanos = now
            intervalReadRequests = 0L
            intervalWriteRequests = 0L
            intervalProbeRequests = 0L
            intervalBytesRead = 0L
            intervalBytesWritten = 0L
            intervalWaitNanos = 0L
            intervalMaxWaitNanos = 0L
            intervalFailures = 0L
        }
    }

    override fun close() {
        val candidate = socket
        runCatching { candidate?.close() }
        lock.withLock {
            if (socket === candidate) closeLocked()
        }
    }

    private fun statusLocked(): ServerStatus {
        val payload = trackedTransactionLocked(RequestKind.PROBE, 0, OP_STATUS, ByteArray(0))
        check(payload.size == STATUS_PAYLOAD_BYTES) {
            "Dolphin returned an invalid ${payload.size}-byte status"
        }
        val data = ByteBuffer.wrap(payload)
        val major = data.short.toInt() and 0xFFFF
        val minor = data.short.toInt() and 0xFFFF
        val capabilities = data.int
        val mem1Size = data.int.toLong() and 0xFFFF_FFFFL
        val mem2Size = data.int.toLong() and 0xFFFF_FFFFL
        val running = data.get().toInt() != 0
        val wii = data.get().toInt() != 0
        val gameIdLength = data.get().toInt() and 0xFF
        data.get()
        val gameIdBytes = ByteArray(8).also(data::get)
        check(major == PROTOCOL_VERSION && minor == 0) {
            "Unsupported Dolphin memory protocol $major.$minor"
        }
        check(capabilities and REQUIRED_CAPABILITIES == REQUIRED_CAPABILITIES) {
            "Dolphin memory service lacks raw read/write support"
        }
        check(running) { "Dolphin has no running emulation" }
        check(gameIdLength in 0..gameIdBytes.size) { "Dolphin returned an invalid game ID" }
        return ServerStatus(
            gameId = gameIdBytes.copyOf(gameIdLength).toString(StandardCharsets.US_ASCII),
            mem1Size = mem1Size,
            mem2Size = mem2Size,
            wii = wii,
        )
    }

    private fun trackedTransactionLocked(
        kind: RequestKind,
        byteCount: Int,
        operation: Int,
        payload: ByteArray,
    ): ByteArray {
        val started = System.nanoTime()
        var failed = true
        try {
            return transactLocked(operation, payload).also { failed = false }
        } catch (error: IOException) {
            // A timed-out or partial framed response cannot be resumed safely.
            // Closing also lets the bridge's ordinary reconnect path take over.
            closeLocked()
            throw error
        } finally {
            recordTelemetryLocked(kind, byteCount, System.nanoTime() - started, failed)
        }
    }

    private fun transactLocked(operation: Int, payload: ByteArray): ByteArray {
        check(isSocketOpen()) { "Dolphin fast-memory socket is closed" }
        val requestId = nextRequestId++.also {
            if (nextRequestId == 0) nextRequestId = 1
        }
        check(payload.size <= MAX_REQUEST_PAYLOAD_BYTES) { "Dolphin memory request is too large" }

        checkNotNull(output).apply {
            writeInt(PROTOCOL_MAGIC)
            writeByte(PROTOCOL_VERSION)
            writeByte(operation)
            writeShort(0)
            writeInt(requestId)
            writeInt(payload.size)
            write(payload)
            flush()
        }

        return checkNotNull(input).let { response ->
            val magic = response.readInt()
            if (magic != PROTOCOL_MAGIC) throw IOException("Invalid Dolphin memory response magic")
            val version = response.readUnsignedByte()
            val responseOperation = response.readUnsignedByte()
            val status = response.readUnsignedShort()
            val responseId = response.readInt()
            val responseSize = response.readInt()
            if (version != PROTOCOL_VERSION || responseOperation != operation || responseId != requestId) {
                throw IOException("Mismatched Dolphin memory response")
            }
            if (responseSize !in 0..MAX_RESPONSE_PAYLOAD_BYTES) {
                throw IOException("Invalid Dolphin memory response size: $responseSize")
            }
            val responsePayload = ByteArray(responseSize)
            try {
                response.readFully(responsePayload)
            } catch (error: EOFException) {
                throw IOException("Dolphin closed an incomplete memory response", error)
            }
            if (status != STATUS_OK) {
                throw IOException("Dolphin memory request failed: ${statusName(status)}")
            }
            responsePayload
        }
    }

    private fun recordTelemetryLocked(kind: RequestKind, byteCount: Int, waitNanos: Long, failed: Boolean) {
        when (kind) {
            RequestKind.READ -> {
                intervalReadRequests++
                sessionReadRequests++
                intervalBytesRead += byteCount
                sessionBytesRead += byteCount
            }
            RequestKind.WRITE -> {
                intervalWriteRequests++
                sessionWriteRequests++
                intervalBytesWritten += byteCount
                sessionBytesWritten += byteCount
            }
            RequestKind.PROBE -> {
                intervalProbeRequests++
                sessionProbeRequests++
            }
        }
        intervalWaitNanos += waitNanos
        sessionWaitNanos += waitNanos
        intervalMaxWaitNanos = maxOf(intervalMaxWaitNanos, waitNanos)
        sessionMaxWaitNanos = maxOf(sessionMaxWaitNanos, waitNanos)
        if (failed) {
            intervalFailures++
            sessionFailures++
        }
    }

    private fun requireHookedLocked() {
        check(logicallyHooked && isSocketOpen()) { "not hooked" }
    }

    private fun isSocketOpen(): Boolean = socket?.let { it.isConnected && !it.isClosed } == true

    private fun closeLocked() {
        logicallyHooked = false
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        lastStatus = null
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

    private data class ServerStatus(
        val gameId: String,
        val mem1Size: Long,
        val mem2Size: Long,
        val wii: Boolean,
    )

    private enum class RequestKind { READ, WRITE, PROBE }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 55021
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 350
        private const val DEFAULT_RESPONSE_TIMEOUT_MILLIS = 2_500
        private const val PROTOCOL_MAGIC = 0x4150444D
        private const val PROTOCOL_VERSION = 1
        private const val OP_STATUS = 1
        private const val OP_READ = 2
        private const val OP_WRITE = 3
        private const val STATUS_OK = 0
        private const val REQUIRED_CAPABILITIES = 0x00000007
        private const val STATUS_PAYLOAD_BYTES = 28
        private const val MAX_TRANSFER_BYTES = 1024 * 1024
        private const val MAX_REQUEST_PAYLOAD_BYTES = MAX_TRANSFER_BYTES + 8
        private const val MAX_RESPONSE_PAYLOAD_BYTES = MAX_TRANSFER_BYTES
        private const val MAX_CONSOLE_ADDRESS = 0xFFFF_FFFFL

        private fun statusName(status: Int): String = when (status) {
            1 -> "invalid request"
            2 -> "unsupported version"
            3 -> "unsupported operation"
            4 -> "invalid address"
            5 -> "transfer too large"
            else -> "status $status"
        }
    }
}
