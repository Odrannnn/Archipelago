package eu.odran.archipelago

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DolphinGdbClientTest {
    @Test
    fun waitsForDolphinsSingleStartupConnectionWithoutTimingOut() {
        FakeGdbServer(acceptDelayMillis = 2_250).use { server ->
            DolphinGdbClient(port = server.port).use { client ->
                client.connect()
                assertTrue(client.isHooked())
            }

            assertEquals(1, server.connectionCount.get())
            server.assertHealthy()
        }
    }

    @Test
    fun blockedStartupHandshakeCanBeCancelled() {
        FakeGdbServer(stallInitialReply = true).use { server ->
            val client = DolphinGdbClient(port = server.port)
            val connector = Executors.newSingleThreadExecutor()
            try {
                val result = connector.submit<Result<Unit>> { runCatching { client.connect() } }
                assertTrue(server.awaitFirstRequest())

                client.close()

                assertTrue(result.get(1, TimeUnit.SECONDS).isFailure)
                server.assertHealthy()
            } finally {
                client.close()
                connector.shutdownNow()
            }
        }
    }

    @Test
    fun resumesDolphinAndExchangesChunkedMemory() {
        FakeGdbServer().use { server ->
            val payload = ByteArray(2_050) { (it and 0xFF).toByte() }
            DolphinGdbClient(port = server.port).use { client ->
                client.connect()

                assertEquals("GZLE99", client.gameId())
                client.writeBytes(0x8030_0000, payload)
                assertArrayEquals(payload, client.readBytes(0x8030_0000, payload.size))
                assertTrue(client.isHooked())

                val telemetry = client.takeTelemetrySnapshot()
                assertEquals(4L, telemetry.intervalReadRequests)
                assertEquals(3L, telemetry.intervalWriteRequests)
                assertEquals(2L, telemetry.intervalProbeRequests)
                assertEquals(2_056L, telemetry.intervalBytesRead)
                assertEquals(2_050L, telemetry.intervalBytesWritten)
                assertEquals(9L, telemetry.intervalRequests)
                assertEquals(0L, telemetry.intervalFailures)
                assertTrue(telemetry.intervalWaitNanos > 0L)
                assertTrue(telemetry.intervalMaxWaitNanos > 0L)

                val display = DolphinTelemetryFormatter.display(
                    telemetry,
                    "GZLE99",
                    server.port,
                    peakRequestsPerSecond = 12.5,
                    peakKibibytesPerSecond = 3.25,
                )
                assertTrue(display.contains("Live"))
                assertTrue(display.contains("peak 12.5 req/s / 3.25 KiB/s"))

                val clearedInterval = client.takeTelemetrySnapshot()
                assertEquals(0L, clearedInterval.intervalRequests)
                assertEquals(9L, clearedInterval.sessionRequests)
                assertEquals(2_056L, clearedInterval.sessionBytesRead)
                assertEquals(2_050L, clearedInterval.sessionBytesWritten)
            }

            assertEquals(1, server.connectionCount.get())
            assertEquals(3, server.requests.count { it.startsWith("M") })
            assertEquals(3, server.requests.count { it.startsWith("m80300000") || it.startsWith("m80300400") || it.startsWith("m80300800") })
            server.assertHealthy()
        }
    }

    @Test
    fun logicalUnhookKeepsDolphinsSingleDebuggerSocketReusable() {
        FakeGdbServer().use { server ->
            DolphinGdbClient(port = server.port).use { client ->
                client.connect()
                client.unHook()
                assertFalse(client.isHooked())
                assertTrue(client.isSocketConnected())

                client.hook()
                assertTrue(client.isHooked())
                assertEquals("GZLE99", client.gameId())
            }

            assertEquals(1, server.connectionCount.get())
            server.assertHealthy()
        }
    }

    @Test
    fun rejectedMemoryRangeDoesNotDestroyTheDebuggerSession() {
        FakeGdbServer(rejectedAddress = 0xDEAD_0000).use { server ->
            DolphinGdbClient(port = server.port).use { client ->
                client.connect()
                try {
                    client.readBytes(0xDEAD_0000, 4)
                    fail("Expected Dolphin's E01 response to fail the read")
                } catch (_: IllegalStateException) {
                    // A guest-memory error is not a TCP transport failure.
                }

                assertTrue(client.isSocketConnected())
                assertEquals("GZLE99", client.gameId())
                val telemetry = client.takeTelemetrySnapshot()
                assertEquals(1L, telemetry.intervalFailures)
                assertEquals(1L, telemetry.sessionFailures)
            }
            server.assertHealthy()
        }
    }

    private class FakeGdbServer(
        private val rejectedAddress: Long? = null,
        private val acceptDelayMillis: Long = 0,
        private val stallInitialReply: Boolean = false,
    ) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private val memory = ConcurrentHashMap<Long, Byte>()
        private val result = executor.submit { serve() }
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val connectionCount = AtomicInteger()
        private val firstRequest = CountDownLatch(1)
        val port: Int get() = server.localPort

        init {
            "GZLE99".toByteArray(StandardCharsets.US_ASCII).forEachIndexed { index, byte ->
                memory[0x8000_0000L + index] = byte
            }
        }

        fun assertHealthy() {
            result.get(2, TimeUnit.SECONDS)
        }

        fun awaitFirstRequest(): Boolean = firstRequest.await(2, TimeUnit.SECONDS)

        override fun close() {
            server.close()
            executor.shutdownNow()
        }

        private fun serve() {
            if (acceptDelayMillis > 0) Thread.sleep(acceptDelayMillis)
            server.accept().use { socket ->
                connectionCount.incrementAndGet()
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                while (!socket.isClosed) {
                    val payload = readRequest(input) ?: return
                    requests.add(payload)
                    firstRequest.countDown()
                    if (stallInitialReply && payload == "?") {
                        while (input.read() >= 0) Unit
                        return
                    }
                    output.write('+'.code)
                    output.flush()
                    when {
                        payload == "?" -> reply(output, "T05thread:1;")
                        payload == "c" -> Unit
                        payload.startsWith("m") -> handleRead(payload, output)
                        payload.startsWith("M") -> handleWrite(payload, output)
                        else -> reply(output, "")
                    }
                }
            }
        }

        private fun handleRead(payload: String, output: BufferedOutputStream) {
            val (addressText, sizeText) = payload.drop(1).split(',', limit = 2)
            val address = addressText.toLong(16)
            val size = sizeText.toInt(16)
            if (address == rejectedAddress) {
                reply(output, "E01")
                return
            }
            val data = ByteArray(size) { memory[address + it] ?: 0 }
            reply(output, data.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
        }

        private fun handleWrite(payload: String, output: BufferedOutputStream) {
            val (header, encoded) = payload.drop(1).split(':', limit = 2)
            val (addressText, sizeText) = header.split(',', limit = 2)
            val address = addressText.toLong(16)
            val size = sizeText.toInt(16)
            require(encoded.length == size * 2)
            repeat(size) { index ->
                memory[address + index] = encoded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            reply(output, "OK")
        }

        private fun readRequest(input: BufferedInputStream): String? {
            var value: Int
            do {
                value = input.read()
                if (value < 0) return null
            } while (value.toChar() != '$')
            val payload = StringBuilder()
            while (true) {
                value = input.read()
                if (value < 0) return null
                if (value.toChar() == '#') break
                payload.append(value.toChar())
            }
            val expected = "${input.read().toChar()}${input.read().toChar()}".toInt(16)
            val actual = payload.toString().toByteArray(StandardCharsets.US_ASCII)
                .fold(0) { checksum, byte -> (checksum + (byte.toInt() and 0xFF)) and 0xFF }
            require(expected == actual)
            return payload.toString()
        }

        private fun reply(output: BufferedOutputStream, payload: String) {
            val checksum = payload.toByteArray(StandardCharsets.US_ASCII)
                .fold(0) { value, byte -> (value + (byte.toInt() and 0xFF)) and 0xFF }
            output.write("\$$payload#${checksum.toString(16).padStart(2, '0')}".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
    }
}
