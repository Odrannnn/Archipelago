package eu.odran.archipelago

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/** The loopback-only protocol implemented by android/mgba-core-patch. */
object BridgeProtocol {
    const val MAGIC = 0x41504231 // "APB1"
    const val PORT = 43056
    const val MAX_PAYLOAD = 4096
    const val MAX_MESSAGE_BYTES = 511
    const val MESSAGE_PROTOCOL_VERSION = 2
    const val GUARDED_WRITE_PROTOCOL_VERSION = 3

    const val HELLO = 1
    const val PING = 2
    const val READ = 3
    const val WRITE = 4
    const val GUARD = 5
    const val ROM_SHA1 = 6
    const val MESSAGE = 7
    const val GUARDED_WRITE = 8

    const val OK = 0
    const val BAD_REQUEST = 1
    const val TOO_LARGE = 2
    const val GUARD_FAILED = 3
    const val UNSUPPORTED = 4

    data class Frame(
        val type: Int,
        val status: Int = OK,
        val id: Int,
        val address: Long = 0,
        val payload: ByteArray = byteArrayOf(),
    )

    fun write(out: DataOutputStream, frame: Frame) {
        require(frame.payload.size <= MAX_PAYLOAD) { "Bridge payload exceeds $MAX_PAYLOAD bytes" }
        out.writeInt(MAGIC)
        out.writeShort(frame.type)
        out.writeShort(frame.status)
        out.writeInt(frame.id)
        out.writeInt(frame.address.toInt())
        out.writeInt(frame.payload.size)
        out.write(frame.payload)
        out.flush()
    }

    fun read(input: DataInputStream): Frame {
        val magic = input.readInt()
        if (magic != MAGIC) throw EOFException("Unexpected bridge magic: 0x${magic.toUInt().toString(16)}")
        val type = input.readUnsignedShort()
        val status = input.readUnsignedShort()
        val id = input.readInt()
        val address = input.readInt().toLong() and 0xffffffffL
        val length = input.readInt()
        require(length in 0..MAX_PAYLOAD) { "Invalid bridge payload length: $length" }
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(type, status, id, address, payload)
    }
}
