package eu.odran.archipelago

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal data class RetroArchNetworkMetrics(
    val commandsSent: Long,
    val responsesReceived: Long,
    val timeouts: Long,
    val transportFailures: Long,
    val safeRetries: Long,
    val socketRotations: Long,
    val unrecoveredFailures: Long,
    val lastRttMs: Double,
    val maxRttMs: Double,
)

private class RetroArchTransportException(message: String, cause: Throwable) :
    IllegalStateException(message, cause)

private class RetroArchUnexpectedResponseException(message: String) :
    IllegalArgumentException(message)

/** Direct client for RetroArch nightly's loopback network-command interface. */
class RetroArchNetworkClient internal constructor(
    private val port: Int = DEFAULT_PORT,
    private val addressMapper: SniAddressMapper = LoRomSniAddressMapper,
    private val steadyCommandTimeoutMs: Int = COMMAND_TIMEOUT_MS,
    private val recoveryCommandTimeoutMs: Int = RECOVERY_COMMAND_TIMEOUT_MS,
    private val socketReceiveTimeoutMs: Int? = null,
) : SniMemoryClient {
    @Volatile private var closed = false
    private val socketLock = Any()
    private var socket = openSocket()
    private var recoveryCommandsRemaining = RECOVERY_COMMAND_COUNT
    private var commandsSent = 0L
    private var responsesReceived = 0L
    private var timeouts = 0L
    private var transportFailures = 0L
    private var safeRetries = 0L
    private var socketRotations = 0L
    private var unrecoveredFailures = 0L
    private var lastRttMs = 0.0
    private var maxRttMs = 0.0
    private val deadlineExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "retroarch-command-deadline").apply { isDaemon = true }
    }

    init {
        require(steadyCommandTimeoutMs > 0) { "RetroArch command timeout must be positive" }
        require(recoveryCommandTimeoutMs >= steadyCommandTimeoutMs) {
            "RetroArch recovery timeout must be at least the steady timeout"
        }
        require(socketReceiveTimeoutMs == null || socketReceiveTimeoutMs > 0) {
            "RetroArch socket receive timeout must be positive"
        }
    }

    private fun openSocket() = DatagramSocket().apply {
        connect(
            InetSocketAddress(
                InetAddress.getByName("127.0.0.1"),
                this@RetroArchNetworkClient.port,
            ),
        )
    }

    @Synchronized
    fun version(): String {
        return safeQuery("VERSION") { response ->
            response.removePrefix("VERSION ").trim().also {
                if (!VERSION_PATTERN.matches(it)) {
                    throw RetroArchUnexpectedResponseException(
                        "Unexpected RetroArch VERSION response: $response",
                    )
                }
            }
        }
    }

    override fun checkStatus(): SniTransportStatus =
        SniTransportStatus("RetroArch ${version()} Network Commands")

    @Synchronized
    override fun readSni(address: Long, length: Int): ByteArray {
        require(length in 1..MAX_READ_SIZE) { "RetroArch read length must be 1..$MAX_READ_SIZE" }
        val busAddress = addressMapper.toBusAddress(address)
        return safeQuery(
            "READ_CORE_MEMORY ${busAddress.toString(16).padStart(6, '0')} $length",
        ) { response ->
            val parts = memoryResponseParts(response, "READ_CORE_MEMORY", busAddress)
            if (parts[2] == "-1") {
                error("RetroArch could not read SNES memory: ${parts.drop(3).joinToString(" ")}")
            }
            if (parts.size != length + 2) {
                throw RetroArchUnexpectedResponseException(
                    "RetroArch returned ${parts.size - 2} bytes; expected $length",
                )
            }
            ByteArray(length) { index ->
                val value = parts[index + 2].toIntOrNull(16)
                if (value == null || value !in 0..255) {
                    throw RetroArchUnexpectedResponseException(
                        "RetroArch returned an invalid byte in: $response",
                    )
                }
                value.toByte()
            }
        }
    }

    @Synchronized
    override fun writeSni(address: Long, data: ByteArray) {
        require(data.isNotEmpty()) { "RetroArch writes may not be empty" }
        var offset = 0
        while (offset < data.size) {
            val chunkSize = minOf(MAX_WRITE_CHUNK_SIZE, data.size - offset)
            writeChunk(address + offset, data, offset, chunkSize)
            offset += chunkSize
        }
    }

    private fun writeChunk(address: Long, data: ByteArray, offset: Int, length: Int) {
        val busAddress = addressMapper.toBusAddress(address)
        val payload = (offset until offset + length).joinToString(" ") { index ->
            "%02x".format(data[index].toInt() and 0xff)
        }
        val command = "WRITE_CORE_MEMORY ${busAddress.toString(16).padStart(6, '0')} $payload"
        val response = try {
            commandOnce(command)
        } catch (error: RetroArchTransportException) {
            unrecoveredFailures++
            rotateSocketAfterFailure()
            throw error
        }
        val parts = response.trim().split(Regex("\\s+"))
        if (parts.size < 3 || parts[0] != "WRITE_CORE_MEMORY") {
            unrecoveredFailures++
            rotateSocketAfterFailure()
            throw RetroArchUnexpectedResponseException("Unexpected RetroArch write response: $response")
        }
        if (parts[1].toLongOrNull(16) != busAddress) {
            unrecoveredFailures++
            rotateSocketAfterFailure()
            throw RetroArchUnexpectedResponseException(
                "RetroArch responded for the wrong write address: $response",
            )
        }
        if (parts[2] == "-1") {
            error("RetroArch could not write SNES memory: ${parts.drop(3).joinToString(" ")}")
        }
        if (parts[2].toIntOrNull() != length) {
            unrecoveredFailures++
            rotateSocketAfterFailure()
            throw RetroArchUnexpectedResponseException(
                "RetroArch wrote ${parts[2]} bytes; expected $length",
            )
        }
    }

    private inline fun <T> safeQuery(value: String, parse: (String) -> T): T {
        var firstFailure: Exception? = null
        repeat(SAFE_QUERY_ATTEMPTS) { attempt ->
            try {
                return parse(commandOnce(value))
            } catch (error: Exception) {
                val retryable = error is RetroArchTransportException ||
                    error is RetroArchUnexpectedResponseException
                if (!retryable) throw error
                rotateSocketAfterFailure()
                if (closed) throw error
                if (attempt + 1 >= SAFE_QUERY_ATTEMPTS) {
                    unrecoveredFailures++
                    throw error
                }
                safeRetries++
                firstFailure = error
            }
        }
        throw checkNotNull(firstFailure)
    }

    private fun memoryResponseParts(response: String, command: String, busAddress: Long): List<String> {
        val parts = response.trim().split(Regex("\\s+"))
        if (parts.size < 3 || parts[0] != command) {
            throw RetroArchUnexpectedResponseException("Unexpected RetroArch memory response: $response")
        }
        if (parts[1].toLongOrNull(16) != busAddress) {
            throw RetroArchUnexpectedResponseException(
                "RetroArch responded for the wrong address: $response",
            )
        }
        return parts
    }

    private fun commandOnce(value: String): String {
        check(!closed) { "RetroArch Network Commands client is closed" }
        val request = (value.trimEnd() + "\n").toByteArray(StandardCharsets.US_ASCII)
        require(request.size <= MAX_COMMAND_PACKET_SIZE) {
            "RetroArch command is ${request.size} bytes; maximum is $MAX_COMMAND_PACKET_SIZE"
        }
        val activeSocket = synchronized(socketLock) {
            check(!closed) { "RetroArch Network Commands client is closed" }
            socket
        }
        val commandTimeoutMs = if (recoveryCommandsRemaining > 0) {
            recoveryCommandTimeoutMs
        } else {
            steadyCommandTimeoutMs
        }
        activeSocket.soTimeout = socketReceiveTimeoutMs ?: commandTimeoutMs
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        val startedAt = System.nanoTime()
        val requestFinished = AtomicBoolean(false)
        val hardDeadlineExpired = AtomicBoolean(false)
        val deadline = deadlineExecutor.schedule({
            if (requestFinished.compareAndSet(false, true)) {
                hardDeadlineExpired.set(true)
                // Android's DatagramSocket SO_RCVTIMEO can remain blocked across an
                // emulator/background transition even after a reply is queued. Closing
                // this exact socket from an independent thread provides a real deadline.
                synchronized(socketLock) {
                    if (!closed && socket === activeSocket) activeSocket.close()
                }
            }
        }, commandTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        commandsSent++
        try {
            activeSocket.send(DatagramPacket(request, request.size))
            activeSocket.receive(packet)
            if (!requestFinished.compareAndSet(false, true)) {
                throw java.net.SocketTimeoutException("Hard request deadline expired")
            }
        } catch (error: IOException) {
            if (error is java.net.SocketTimeoutException || hardDeadlineExpired.get()) {
                timeouts++
            } else {
                transportFailures++
            }
            throw RetroArchTransportException(
                "RetroArch did not answer on 127.0.0.1:$port. Use an Android nightly build and enable Settings > Network > Network Commands.",
                error,
            )
        } finally {
            requestFinished.set(true)
            deadline.cancel(false)
        }
        val rttMs = (System.nanoTime() - startedAt) / 1_000_000.0
        responsesReceived++
        lastRttMs = rttMs
        maxRttMs = max(maxRttMs, rttMs)
        if (recoveryCommandsRemaining > 0) recoveryCommandsRemaining--
        return packet.data.decodeToString(packet.offset, packet.offset + packet.length).trim()
    }

    private fun rotateSocketAfterFailure() {
        synchronized(socketLock) {
            socket.close()
            if (closed) return
            socket = openSocket()
            recoveryCommandsRemaining = RECOVERY_COMMAND_COUNT
            socketRotations++
        }
    }

    @Synchronized
    internal fun metricsSnapshot() = RetroArchNetworkMetrics(
        commandsSent = commandsSent,
        responsesReceived = responsesReceived,
        timeouts = timeouts,
        transportFailures = transportFailures,
        safeRetries = safeRetries,
        socketRotations = socketRotations,
        unrecoveredFailures = unrecoveredFailures,
        lastRttMs = lastRttMs,
        maxRttMs = maxRttMs,
    )

    internal val isClosed: Boolean
        get() = closed

    override fun close() {
        if (closed) return
        closed = true
        synchronized(socketLock) {
            socket.close()
        }
        deadlineExecutor.shutdownNow()
    }

    companion object {
        const val DEFAULT_PORT = 55355
        private const val COMMAND_TIMEOUT_MS = 900
        // Desktop SNI allows a memory request up to five seconds to complete. Four UDP
        // attempts give Network Commands the same recovery window while rotating the
        // socket after every lost or unusable response.
        private const val RECOVERY_COMMAND_TIMEOUT_MS = 1_350
        private const val RECOVERY_COMMAND_COUNT = 2
        private const val SAFE_QUERY_ATTEMPTS = 4
        private const val MAX_COMMAND_PACKET_SIZE = 2_047
        private const val MAX_PACKET_SIZE = 65_507
        private const val MAX_READ_SIZE = 2_048
        internal const val MAX_WRITE_CHUNK_SIZE = 640
        private val VERSION_PATTERN = Regex("\\d+\\.\\d+\\.\\d+.*")
    }
}

/** Stable Chaquopy-facing handle which survives a temporary RetroArch pause. */
class AndroidSniBackend(client: SniMemoryClient) {
    @Volatile private var client: SniMemoryClient? = client

    fun read(address: Long, length: Int): ByteArray = current().readSni(address, length)

    fun write(address: Long, data: ByteArray) = current().writeSni(address, data)

    fun attach(newClient: SniMemoryClient) {
        client = newClient
    }

    fun detach(oldClient: SniMemoryClient) {
        if (client === oldClient) client = null
    }

    private fun current(): SniMemoryClient =
        checkNotNull(client) { "SNES emulator memory is temporarily unavailable" }
}
