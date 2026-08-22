package eu.odran.archipelago

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RetroArchNetworkClientTest {
    @Test
    fun defaultPortCreatesAValidUdpClient() {
        RetroArchNetworkClient().use { }
    }

    @Test
    fun translatesLoRomSniAddressesToTheLibretroBus() {
        assertEquals(0x80FFC0L, LoRomSniAddressMapper.toBusAddress(0x007FC0))
        assertEquals(0x818000L, LoRomSniAddressMapper.toBusAddress(0x008000))
        assertEquals(0x700000L, LoRomSniAddressMapper.toBusAddress(0xE00000))
        assertEquals(0x710000L, LoRomSniAddressMapper.toBusAddress(0xE08000))
        assertEquals(0x7E0000L, LoRomSniAddressMapper.toBusAddress(0xF50000))
        assertEquals(0x7FFFFFL, LoRomSniAddressMapper.toBusAddress(0xF6FFFF))
    }

    @Test
    fun parsesNintendo64ContentStatus() = withServer { server, executor ->
        val exchange = executor.submit(Callable {
            val request = receive(server)
            respond(
                server,
                request.packet,
                "GET_STATUS PLAYING Nintendo 64,Paper Mario Player1.z64,mupen64plus_next_gles3\n",
            )
            request.text
        })

        RetroArchNetworkClient(
            port = server.localPort,
            steadyCommandTimeoutMs = 80,
            recoveryCommandTimeoutMs = 80,
        ).use { client ->
            val status = client.contentStatus()
            assertEquals("GET_STATUS\n", exchange.get(2, TimeUnit.SECONDS))
            assertTrue(status.isPlaying)
            assertTrue(status.isNintendo64)
            assertEquals("Paper Mario Player1.z64", status.content)
        }
    }

    @Test
    fun timeoutRotatesSocketAndRetriesSafeVersionQuery() = withServer { server, executor ->
        val exchange = executor.submit(Callable {
            val first = receive(server)
            val second = receive(server)
            respond(server, second.packet, "1.22.2\n")
            first.packet.port to second.packet.port
        })

        RetroArchNetworkClient(
            port = server.localPort,
            steadyCommandTimeoutMs = 80,
            recoveryCommandTimeoutMs = 80,
        ).use { client ->
            assertEquals("1.22.2", client.version())
            val sourcePorts = exchange.get(2, TimeUnit.SECONDS)
            assertNotEquals(sourcePorts.first, sourcePorts.second)
            val metrics = client.metricsSnapshot()
            assertEquals(2L, metrics.commandsSent)
            assertEquals(1L, metrics.responsesReceived)
            assertEquals(1L, metrics.timeouts)
            assertEquals(1L, metrics.safeRetries)
            assertEquals(1L, metrics.socketRotations)
            assertEquals(0L, metrics.unrecoveredFailures)
        }
    }

    @Test
    fun safeReadSurvivesThreeConsecutiveLostReplies() = withServer { server, executor ->
        val exchange = executor.submit(Callable {
            val sourcePorts = mutableListOf<Int>()
            repeat(4) { attempt ->
                val request = receive(server)
                sourcePorts += request.packet.port
                if (attempt == 3) {
                    respond(server, request.packet, "READ_CORE_MEMORY 1234 2a\n")
                }
            }
            sourcePorts
        })

        RetroArchNetworkClient(
            port = server.localPort,
            addressMapper = SniAddressMapper { it },
            steadyCommandTimeoutMs = 80,
            recoveryCommandTimeoutMs = 80,
        ).use { client ->
            assertArrayEquals(byteArrayOf(0x2a), client.readSni(0x1234, 1))
            val sourcePorts = exchange.get(2, TimeUnit.SECONDS)
            assertEquals(4, sourcePorts.distinct().size)
            val metrics = client.metricsSnapshot()
            assertEquals(4L, metrics.commandsSent)
            assertEquals(3L, metrics.timeouts)
            assertEquals(3L, metrics.safeRetries)
            assertEquals(3L, metrics.socketRotations)
            assertEquals(0L, metrics.unrecoveredFailures)
        }
    }

    @Test
    fun lateReplyToAbandonedSocketCannotPoisonRetry() = withServer { server, executor ->
        val exchange = executor.submit(Callable {
            val first = receive(server)
            Thread.sleep(100)
            respond(server, first.packet, "0.0.0-stale\n")
            val second = receive(server)
            respond(server, second.packet, "1.22.2\n")
            first.packet.port to second.packet.port
        })

        RetroArchNetworkClient(
            port = server.localPort,
            steadyCommandTimeoutMs = 80,
            recoveryCommandTimeoutMs = 80,
        ).use { client ->
            assertEquals("1.22.2", client.version())
            val sourcePorts = exchange.get(2, TimeUnit.SECONDS)
            assertNotEquals(sourcePorts.first, sourcePorts.second)
            assertEquals(1L, client.metricsSnapshot().timeouts)
            assertEquals(1L, client.metricsSnapshot().safeRetries)
        }
    }

    @Test
    fun wrongAddressReadReplyIsRetriedOnFreshSocket() = withServer { server, executor ->
        val exchange = executor.submit(Callable {
            val first = receive(server)
            respond(server, first.packet, "READ_CORE_MEMORY deadbeef 01 02\n")
            val second = receive(server)
            respond(server, second.packet, "READ_CORE_MEMORY 1234 01 02\n")
            first.packet.port to second.packet.port
        })

        RetroArchNetworkClient(
            port = server.localPort,
            addressMapper = SniAddressMapper { it },
            steadyCommandTimeoutMs = 80,
            recoveryCommandTimeoutMs = 80,
        ).use { client ->
            assertArrayEquals(byteArrayOf(1, 2), client.readSni(0x1234, 2))
            val sourcePorts = exchange.get(2, TimeUnit.SECONDS)
            assertNotEquals(sourcePorts.first, sourcePorts.second)
            assertEquals(1L, client.metricsSnapshot().safeRetries)
            assertEquals(1L, client.metricsSnapshot().socketRotations)
        }
    }

    @Test
    fun timedOutWriteRotatesSocketWithoutRetryingWrite() = withServer { server, executor ->
        val exchange = executor.submit(Callable {
            receive(server)
            server.soTimeout = 250
            try {
                receive(server)
                true
            } catch (_: SocketTimeoutException) {
                false
            }
        })

        RetroArchNetworkClient(
            port = server.localPort,
            addressMapper = SniAddressMapper { it },
            steadyCommandTimeoutMs = 80,
            recoveryCommandTimeoutMs = 80,
        ).use { client ->
            assertThrows(IllegalStateException::class.java) {
                client.writeSni(0x2000, byteArrayOf(1, 2, 3))
            }
            assertEquals(false, exchange.get(2, TimeUnit.SECONDS))
            assertEquals(1L, client.metricsSnapshot().commandsSent)
            assertEquals(0L, client.metricsSnapshot().safeRetries)
            assertEquals(1L, client.metricsSnapshot().socketRotations)
            assertEquals(1L, client.metricsSnapshot().unrecoveredFailures)
        }
    }

    @Test
    fun closeInterruptsBlockedReceiveWithoutWaitingForTimeout() = withServer { server, executor ->
        val client = RetroArchNetworkClient(
            port = server.localPort,
            steadyCommandTimeoutMs = 2_000,
            recoveryCommandTimeoutMs = 2_000,
        )
        val versionCall = executor.submit(Callable { runCatching { client.version() } })
        receive(server)

        val startedAt = System.nanoTime()
        client.close()
        val closeElapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(versionCall.get(1, TimeUnit.SECONDS).isFailure)
        assertTrue("close took ${closeElapsedMs}ms", closeElapsedMs < 500)
        assertTrue(client.isClosed)
    }

    @Test
    fun nonBlockingReceiveHonorsCommandDeadline() = withServer { server, executor ->
        val exchange = executor.submit(Callable {
            List(4) { receive(server).packet.port }
        })

        val startedAt = System.nanoTime()
        RetroArchNetworkClient(
            port = server.localPort,
            steadyCommandTimeoutMs = 80,
            recoveryCommandTimeoutMs = 80,
        ).use { client ->
            assertThrows(IllegalStateException::class.java) { client.version() }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertEquals(4, exchange.get(2, TimeUnit.SECONDS).distinct().size)
            assertTrue("non-blocking deadline took ${elapsedMs}ms", elapsedMs < 1_000)
            val metrics = client.metricsSnapshot()
            assertEquals(4L, metrics.commandsSent)
            assertEquals(4L, metrics.timeouts)
            assertEquals(3L, metrics.safeRetries)
            assertEquals(4L, metrics.socketRotations)
            assertEquals(1L, metrics.unrecoveredFailures)
        }
    }

    @Test
    fun largeWritesAreSplitBelowRetroArchCommandBufferLimit() = withServer { server, executor ->
        val exchange = executor.submit(Callable {
            List(3) {
                val request = receive(server)
                val parts = request.text.trim().split(Regex("\\s+"))
                val byteCount = parts.size - 2
                respond(server, request.packet, "WRITE_CORE_MEMORY ${parts[1]} $byteCount\n")
                Triple(parts[1].toLong(16), byteCount, request.packet.length)
            }
        })
        val data = ByteArray(1_300) { (it and 0xff).toByte() }

        RetroArchNetworkClient(
            port = server.localPort,
            addressMapper = SniAddressMapper { it },
            steadyCommandTimeoutMs = 80,
            recoveryCommandTimeoutMs = 80,
        ).use { client ->
            client.writeSni(0x1000, data)
            val requests = exchange.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(0x1000L, 0x1280L, 0x1500L), requests.map { it.first })
            assertEquals(listOf(640, 640, 20), requests.map { it.second })
            assertTrue(requests.all { it.third <= 2_047 })
            assertEquals(3L, client.metricsSnapshot().commandsSent)
            assertEquals(0L, client.metricsSnapshot().safeRetries)
        }
    }

    private fun withServer(test: (DatagramSocket, java.util.concurrent.ExecutorService) -> Unit) {
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 2_000
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            test(server, executor)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    private data class Request(val packet: DatagramPacket, val text: String)

    private fun receive(server: DatagramSocket): Request {
        val buffer = ByteArray(4_096)
        val packet = DatagramPacket(buffer, buffer.size)
        server.receive(packet)
        return Request(
            packet = packet,
            text = packet.data.decodeToString(packet.offset, packet.offset + packet.length),
        )
    }

    private fun respond(server: DatagramSocket, request: DatagramPacket, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        server.send(DatagramPacket(bytes, bytes.size, request.address, request.port))
    }
}
