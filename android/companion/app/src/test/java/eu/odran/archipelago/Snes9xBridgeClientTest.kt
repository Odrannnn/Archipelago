package eu.odran.archipelago

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Snes9xBridgeClientTest {
    @Test
    fun exchangesSniMemoryAndReportsResetGeneration() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val serverResult = executor.submit {
            server.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val hello = BridgeProtocol.read(input)
                assertEquals(BridgeProtocol.HELLO, hello.type)
                respond(output, hello, byteArrayOf(1, 3, 0, 0, 0, 7))

                val ping = BridgeProtocol.read(input)
                assertEquals(BridgeProtocol.PING, ping.type)
                respond(output, ping, byteArrayOf(0, 0, 0, 8))

                val read = BridgeProtocol.read(input)
                assertEquals(BridgeProtocol.READ, read.type)
                assertEquals(0xF50998L, read.address)
                assertArrayEquals(byteArrayOf(0, 0, 0, 2), read.payload)
                respond(output, read, byteArrayOf(0x08, 0x00))

                val write = BridgeProtocol.read(input)
                assertEquals(BridgeProtocol.WRITE, write.type)
                assertEquals(0xE02602L, write.address)
                assertArrayEquals(byteArrayOf(4, 5, 6), write.payload)
                respond(output, write)
            }
        }

        Snes9xBridgeClient(server.localPort).use { client ->
            client.connect()
            assertEquals(7L, client.checkStatus().resetGeneration)
            assertEquals(8L, client.checkStatus().resetGeneration)
            assertArrayEquals(byteArrayOf(0x08, 0x00), client.readSni(0xF50998, 2))
            client.writeSni(0xE02602, byteArrayOf(4, 5, 6))
        }

        serverResult.get(2, TimeUnit.SECONDS)
        executor.shutdownNow()
        server.close()
    }

    private fun respond(
        output: DataOutputStream,
        request: BridgeProtocol.Frame,
        payload: ByteArray = byteArrayOf(),
    ) = BridgeProtocol.write(
        output,
        BridgeProtocol.Frame(
            type = request.type,
            id = request.id,
            address = request.address,
            payload = payload,
        ),
    )
}
