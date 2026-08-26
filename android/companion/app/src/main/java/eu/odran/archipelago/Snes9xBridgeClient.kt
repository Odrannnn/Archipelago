package eu.odran.archipelago

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/** SNI-domain client for the reset-stable bridge built into the custom SNES9x core. */
class Snes9xBridgeClient(
    private val port: Int = DEFAULT_PORT,
) : SniMemoryClient {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var nextId = 1
    private var protocolVersion = 0

    fun connect(timeoutMs: Int = CONNECT_TIMEOUT_MS) {
        check(socket == null) { "Already connected" }
        val connected = Socket()
        connected.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
        connected.soTimeout = REQUEST_TIMEOUT_MS
        socket = connected
        input = DataInputStream(connected.getInputStream())
        output = DataOutputStream(connected.getOutputStream())
    }

    override fun checkStatus(): SniTransportStatus {
        val response = request(if (protocolVersion == 0) BridgeProtocol.HELLO else BridgeProtocol.PING)
        return if (protocolVersion == 0) {
            require(response.payload.size == 6) { "Invalid SNES9x bridge HELLO response" }
            protocolVersion = response.payload[0].toInt() and 0xff
            val platform = response.payload[1].toInt() and 0xff
            require(protocolVersion == PROTOCOL_VERSION) {
                "SNES9x bridge protocol $protocolVersion is unsupported; version $PROTOCOL_VERSION is required"
            }
            require(platform == PLATFORM_SNES) { "Bridge platform $platform is not SNES" }
            SniTransportStatus(
                description = "SNES9x AP bridge protocol $protocolVersion",
                resetGeneration = readGeneration(response.payload, 2),
            )
        } else {
            require(response.payload.size == 4) { "Invalid SNES9x bridge PING response" }
            SniTransportStatus(
                description = "SNES9x AP bridge protocol $protocolVersion",
                resetGeneration = readGeneration(response.payload, 0),
            )
        }
    }

    override fun readSni(address: Long, length: Int): ByteArray {
        require(length in 1..BridgeProtocol.MAX_PAYLOAD) { "SNES9x read length is out of range" }
        val requestLength = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(length).array()
        return request(BridgeProtocol.READ, address, requestLength).payload.also {
            require(it.size == length) { "Short SNES9x bridge read" }
        }
    }

    override fun writeSni(address: Long, data: ByteArray) {
        require(data.isNotEmpty()) { "SNES9x writes may not be empty" }
        request(BridgeProtocol.WRITE, address, data)
    }

    private fun request(
        type: Int,
        address: Long = 0,
        payload: ByteArray = byteArrayOf(),
    ): BridgeProtocol.Frame {
        val id = nextId++
        val response = try {
            BridgeProtocol.write(
                checkNotNull(output) { "SNES9x bridge is not connected" },
                BridgeProtocol.Frame(type = type, id = id, address = address, payload = payload),
            )
            BridgeProtocol.read(checkNotNull(input) { "SNES9x bridge is not connected" })
        } catch (error: Exception) {
            close()
            throw error
        }
        if (response.type != type || response.id != id) {
            close()
            error("Mismatched SNES9x bridge response")
        }
        check(response.status == BridgeProtocol.OK) {
            "SNES9x bridge request failed with status ${response.status}"
        }
        return response
    }

    private fun readGeneration(payload: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(payload, offset, Int.SIZE_BYTES).int.toLong() and 0xffffffffL

    override fun close() {
        socket?.close()
        socket = null
        input = null
        output = null
        protocolVersion = 0
    }

    companion object {
        const val DEFAULT_PORT = 43057
        const val PROTOCOL_VERSION = 1
        const val PLATFORM_SNES = 3
        private const val CONNECT_TIMEOUT_MS = 250
        private const val REQUEST_TIMEOUT_MS = 1_500
    }
}
