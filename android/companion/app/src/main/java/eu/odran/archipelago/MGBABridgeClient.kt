package eu.odran.archipelago

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Blocking client for the local mGBA core bridge. Use only from a worker
 * thread. All memory access remains serialized by mGBA in retro_run().
 */
class MGBABridgeClient {
    data class MemoryGuard(val address: Long, val expected: ByteArray)

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var nextId = 1
    private var protocolVersion = 0

    fun connect(timeoutMs: Int = 1_500) {
        check(socket == null) { "Already connected" }
        val newSocket = Socket()
        newSocket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), BridgeProtocol.PORT), timeoutMs)
        newSocket.soTimeout = timeoutMs
        socket = newSocket
        input = DataInputStream(newSocket.getInputStream())
        output = DataOutputStream(newSocket.getOutputStream())
    }

    fun close() {
        socket?.close()
        socket = null
        input = null
        output = null
    }

    fun hello(): Pair<Int, Int> {
        val response = request(BridgeProtocol.HELLO)
        require(response.payload.size == 2) { "Invalid HELLO response" }
        protocolVersion = response.payload[0].toInt() and 0xff
        return protocolVersion to (response.payload[1].toInt() and 0xff)
    }

    fun ping() = request(BridgeProtocol.PING)

    fun read(address: Long, length: Int): ByteArray {
        require(length in 0..BridgeProtocol.MAX_PAYLOAD)
        val requestLength = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(length).array()
        return request(BridgeProtocol.READ, address, requestLength).also { response ->
            require(response.payload.size == length) { "Short bridge read" }
        }.payload
    }

    fun guard(address: Long, expected: ByteArray): Boolean {
        val response = request(BridgeProtocol.GUARD, address, expected, allowGuardFailure = true)
        return response.status == BridgeProtocol.OK
    }

    fun write(address: Long, value: ByteArray) {
        request(BridgeProtocol.WRITE, address, value)
    }

    /** Validates every guard and applies one write in the same emulated frame. */
    fun guardedWrite(address: Long, value: ByteArray, guards: List<MemoryGuard>): Boolean {
        check(protocolVersion >= BridgeProtocol.GUARDED_WRITE_PROTOCOL_VERSION) {
            "This game requires mGBA Archipelago bridge protocol ${BridgeProtocol.GUARDED_WRITE_PROTOCOL_VERSION}"
        }
        require(guards.isNotEmpty()) { "A guarded write needs at least one guard" }
        val payloadSize = Short.SIZE_BYTES + guards.sumOf {
            Int.SIZE_BYTES + Short.SIZE_BYTES + it.expected.size
        } + Short.SIZE_BYTES + value.size
        require(payloadSize <= BridgeProtocol.MAX_PAYLOAD) { "Guarded write payload is too large" }
        val payload = ByteBuffer.allocate(payloadSize)
        payload.putShort(guards.size.toShort())
        guards.forEach { guard ->
            require(guard.expected.size <= 0xffff) { "Guard is too large" }
            payload.putInt(guard.address.toInt())
            payload.putShort(guard.expected.size.toShort())
            payload.put(guard.expected)
        }
        require(value.size <= 0xffff) { "Write is too large" }
        payload.putShort(value.size.toShort())
        payload.put(value)
        val response = request(
            BridgeProtocol.GUARDED_WRITE,
            address,
            payload.array(),
            allowGuardFailure = true,
        )
        return response.status == BridgeProtocol.OK
    }

    fun romSha1(): String = request(BridgeProtocol.ROM_SHA1).payload.let { bytes ->
        require(bytes.size == 20) { "Invalid SHA-1 response" }
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Displays a short notification through RetroArch's on-screen display. */
    fun showMessage(message: String) {
        if (protocolVersion < BridgeProtocol.MESSAGE_PROTOCOL_VERSION) return
        require(message.isNotBlank()) { "Bridge message must not be blank" }
        request(BridgeProtocol.MESSAGE, payload = boundedUtf8(message))
    }

    /**
     * The mGBA bridge addresses the GBA system bus.  APWorld clients normally
     * use offsets into emulator-specific memory domains, so game profiles use
     * this helper instead of leaking those offsets into transport code.
     */
    fun read(domain: GbaMemoryDomain, offset: Long, length: Int): ByteArray =
        read(domain.toBusAddress(offset), length)

    fun guard(domain: GbaMemoryDomain, offset: Long, expected: ByteArray): Boolean =
        guard(domain.toBusAddress(offset), expected)

    fun write(domain: GbaMemoryDomain, offset: Long, value: ByteArray) {
        write(domain.toBusAddress(offset), value)
    }

    fun guardedWrite(
        domain: GbaMemoryDomain,
        offset: Long,
        value: ByteArray,
        guards: List<Triple<GbaMemoryDomain, Long, ByteArray>>,
    ): Boolean = guardedWrite(
        domain.toBusAddress(offset),
        value,
        guards.map { (guardDomain, guardOffset, expected) ->
            MemoryGuard(guardDomain.toBusAddress(guardOffset), expected)
        },
    )

    private fun boundedUtf8(message: String): ByteArray {
        val bytes = message.encodeToByteArray()
        if (bytes.size <= BridgeProtocol.MAX_MESSAGE_BYTES) return bytes

        val bounded = StringBuilder()
        var index = 0
        while (index < message.length) {
            val codePoint = message.codePointAt(index)
            val candidate = bounded.toString() + String(Character.toChars(codePoint))
            if (candidate.encodeToByteArray().size > BridgeProtocol.MAX_MESSAGE_BYTES) break
            bounded.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
        return bounded.toString().encodeToByteArray()
    }

    private fun request(
        type: Int,
        address: Long = 0,
        payload: ByteArray = byteArrayOf(),
        allowGuardFailure: Boolean = false,
    ): BridgeProtocol.Frame {
        val id = nextId++
        val out = checkNotNull(output) { "Not connected" }
        val inStream = checkNotNull(input) { "Not connected" }
        BridgeProtocol.write(out, BridgeProtocol.Frame(type, id = id, address = address, payload = payload))
        val response = BridgeProtocol.read(inStream)
        require(response.type == type && response.id == id) { "Mismatched bridge response" }
        if (response.status != BridgeProtocol.OK && !(allowGuardFailure && response.status == BridgeProtocol.GUARD_FAILED)) {
            error("mGBA bridge request failed: ${response.status}")
        }
        return response
    }
}
