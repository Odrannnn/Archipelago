package eu.odran.archipelago

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/** Direct client for RetroArch nightly's loopback network-command interface. */
class RetroArchNetworkClient(
    private val port: Int = DEFAULT_PORT,
    private val addressMapper: SniAddressMapper = LoRomSniAddressMapper,
) : SniMemoryClient {
    private val socket = DatagramSocket().apply {
        connect(
            InetSocketAddress(
                InetAddress.getByName("127.0.0.1"),
                this@RetroArchNetworkClient.port,
            ),
        )
        soTimeout = COMMAND_TIMEOUT_MS
    }

    @Synchronized
    fun version(): String {
        val response = command("VERSION")
        return response.removePrefix("VERSION ").trim().also {
            require(VERSION_PATTERN.matches(it)) { "Unexpected RetroArch VERSION response: $response" }
        }
    }

    override fun checkStatus(): SniTransportStatus =
        SniTransportStatus("RetroArch ${version()} Network Commands")

    @Synchronized
    override fun readSni(address: Long, length: Int): ByteArray {
        require(length in 1..MAX_READ_SIZE) { "RetroArch read length must be 1..$MAX_READ_SIZE" }
        val busAddress = addressMapper.toBusAddress(address)
        val response = command("READ_CORE_MEMORY ${busAddress.toString(16).padStart(6, '0')} $length")
        val parts = response.trim().split(Regex("\\s+"))
        require(parts.size >= 3 && parts[0] == "READ_CORE_MEMORY") {
            "Unexpected RetroArch memory response: $response"
        }
        require(parts[1].toLongOrNull(16) == busAddress) {
            "RetroArch responded for the wrong address: $response"
        }
        if (parts[2] == "-1") {
            error("RetroArch could not read SNES memory: ${parts.drop(3).joinToString(" ")}")
        }
        require(parts.size == length + 2) {
            "RetroArch returned ${parts.size - 2} bytes; expected $length"
        }
        return ByteArray(length) { index ->
            parts[index + 2].toInt(16).also { require(it in 0..255) }.toByte()
        }
    }

    @Synchronized
    override fun writeSni(address: Long, data: ByteArray) {
        require(data.isNotEmpty()) { "RetroArch writes may not be empty" }
        val busAddress = addressMapper.toBusAddress(address)
        val payload = data.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
        val response = command("WRITE_CORE_MEMORY ${busAddress.toString(16).padStart(6, '0')} $payload")
        val parts = response.trim().split(Regex("\\s+"))
        require(parts.size >= 3 && parts[0] == "WRITE_CORE_MEMORY") {
            "Unexpected RetroArch write response: $response"
        }
        require(parts[1].toLongOrNull(16) == busAddress) {
            "RetroArch responded for the wrong address: $response"
        }
        if (parts[2] == "-1") {
            error("RetroArch could not write SNES memory: ${parts.drop(3).joinToString(" ")}")
        }
        require(parts[2].toIntOrNull() == data.size) {
            "RetroArch wrote ${parts[2]} bytes; expected ${data.size}"
        }
    }

    private fun command(value: String): String {
        val request = (value.trimEnd() + "\n").toByteArray(StandardCharsets.US_ASCII)
        socket.send(DatagramPacket(request, request.size))
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
        } catch (error: SocketTimeoutException) {
            throw IllegalStateException(
                "RetroArch did not answer on 127.0.0.1:$port. Use an Android nightly build and enable Settings > Network > Network Commands.",
                error,
            )
        }
        return packet.data.decodeToString(packet.offset, packet.offset + packet.length).trim()
    }

    override fun close() = socket.close()

    companion object {
        const val DEFAULT_PORT = 55355
        private const val COMMAND_TIMEOUT_MS = 900
        private const val MAX_PACKET_SIZE = 65_507
        private const val MAX_READ_SIZE = 2_048
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
