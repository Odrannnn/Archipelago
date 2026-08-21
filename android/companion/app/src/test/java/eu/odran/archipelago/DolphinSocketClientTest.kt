package eu.odran.archipelago

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolphinSocketClientTest {
    @Test
    fun exchangesRawMemoryAndPreservesLogicalDmeHooking() {
        FakeDolphinMemoryServer().use { server ->
            DolphinSocketClient(port = server.port).use { client ->
                client.connect()
                assertTrue(client.isHooked())
                assertEquals("GZLE99", client.gameId())

                client.writeBytes(0x80001000, byteArrayOf(0x12, 0x34, 0x56, 0x78))
                assertArrayEquals(
                    byteArrayOf(0x12, 0x34, 0x56, 0x78),
                    client.readBytes(0x80001000, 4),
                )

                client.unHook()
                assertFalse(client.isHooked())
                assertTrue(client.isSocketConnected())
                client.hook()
                assertTrue(client.isHooked())

                val telemetry = client.takeTelemetrySnapshot()
                assertEquals(1, telemetry.sessionReadRequests)
                assertEquals(1, telemetry.sessionWriteRequests)
                assertTrue(telemetry.sessionProbeRequests >= 2)
                assertEquals(0, telemetry.sessionFailures)
            }
        }
    }

    @Test
    fun acceptsAReplacementClientWithoutRestartingEmulation() {
        FakeDolphinMemoryServer().use { server ->
            DolphinSocketClient(port = server.port).use { first ->
                first.connect()
                assertEquals("GZLE99", first.gameId())
            }
            DolphinSocketClient(port = server.port).use { second ->
                second.connect()
                assertEquals("GZLE99", second.gameId())
                assertTrue(second.isHooked())
            }
            assertEquals(2, server.acceptedClients)
        }
    }

    @Test
    fun timesOutAnUnresponsiveEmulatorAndClosesTheFramedConnection() {
        FakeDolphinMemoryServer(stallReads = true).use { server ->
            DolphinSocketClient(port = server.port, responseTimeoutMillis = 150).use { client ->
                client.connect()

                val started = System.nanoTime()
                val error = runCatching { client.readBytes(0x80001000, 4) }.exceptionOrNull()
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000

                assertTrue(error is java.io.IOException)
                assertTrue(error?.cause is SocketTimeoutException)
                assertTrue("Socket timeout took ${elapsedMillis}ms", elapsedMillis < 2_000)
                assertFalse(client.isSocketConnected())
            }
        }
    }

    private class FakeDolphinMemoryServer(
        private val stallReads: Boolean = false,
    ) : Closeable {
        private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        private val memory = ConcurrentHashMap<Long, Byte>()
        @Volatile private var running = true
        @Volatile var acceptedClients = 0
            private set
        val port: Int = server.localPort
        private val worker = thread(name = "fake-dolphin-memory", isDaemon = true) {
            while (running) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                acceptedClients++
                socket.use(::serve)
            }
        }

        private fun serve(socket: Socket) {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            while (running && !socket.isClosed) {
                val magic = runCatching { input.readInt() }.getOrNull() ?: return
                if (magic != MAGIC) return
                val version = input.readUnsignedByte()
                val operation = input.readUnsignedByte()
                input.readUnsignedShort()
                val requestId = input.readInt()
                val payloadSize = input.readInt()
                val payload = ByteArray(payloadSize)
                input.readFully(payload)
                if (version != VERSION) {
                    respond(output, operation, 2, requestId, ByteArray(0))
                    continue
                }
                when (operation) {
                    OP_STATUS -> respond(output, operation, 0, requestId, statusPayload())
                    OP_READ -> {
                        if (stallReads) continue
                        val request = ByteBuffer.wrap(payload)
                        val address = request.int.toLong() and 0xFFFF_FFFFL
                        val size = request.int
                        val data = ByteArray(size) { offset -> memory[address + offset] ?: 0 }
                        respond(output, operation, 0, requestId, data)
                    }
                    OP_WRITE -> {
                        val request = ByteBuffer.wrap(payload)
                        val address = request.int.toLong() and 0xFFFF_FFFFL
                        val size = request.int
                        repeat(size) { offset -> memory[address + offset] = request.get() }
                        respond(output, operation, 0, requestId, ByteArray(0))
                    }
                    else -> respond(output, operation, 3, requestId, ByteArray(0))
                }
            }
        }

        private fun statusPayload(): ByteArray = ByteBuffer.allocate(28)
            .putShort(VERSION.toShort())
            .putShort(0.toShort())
            .putInt(7)
            .putInt(0x01800000)
            .putInt(0)
            .put(1.toByte())
            .put(0.toByte())
            .put(6.toByte())
            .put(0.toByte())
            .put("GZLE99".toByteArray())
            .put(0.toByte())
            .put(0.toByte())
            .array()

        private fun respond(
            output: DataOutputStream,
            operation: Int,
            status: Int,
            requestId: Int,
            payload: ByteArray,
        ) {
            output.writeInt(MAGIC)
            output.writeByte(VERSION)
            output.writeByte(operation)
            output.writeShort(status)
            output.writeInt(requestId)
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }

        override fun close() {
            running = false
            runCatching { server.close() }
            worker.join(2_000)
        }

        companion object {
            private const val MAGIC = 0x4150444D
            private const val VERSION = 1
            private const val OP_STATUS = 1
            private const val OP_READ = 2
            private const val OP_WRITE = 3
        }
    }
}
