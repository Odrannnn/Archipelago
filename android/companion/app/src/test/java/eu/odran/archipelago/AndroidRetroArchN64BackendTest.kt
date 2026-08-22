package eu.odran.archipelago

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidRetroArchN64BackendTest {
    @Test
    fun `reads canonical logical bytes from word-swapped N64 memory`() {
        val memory = FakeCoreMemoryAccess(byteXorMask = 3)
        memory.putLogical(AndroidRetroArchN64Backend.RDRAM_BASE, 0x20, byteArrayOf(1, 2, 3, 4), 3)
        val backend = AndroidRetroArchN64Backend(memory)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), backend.readRdram(0x20, 4))
        assertEquals("N64", backend.getSystem())
    }

    @Test
    fun `writes logical bytes with the detected word mapping`() {
        val memory = FakeCoreMemoryAccess(byteXorMask = 3)
        val backend = AndroidRetroArchN64Backend(memory)

        backend.writeRdram(0x10, byteArrayOf(0x11, 0x22, 0x33, 0x44))

        assertEquals(0x11.toByte(), memory[AndroidRetroArchN64Backend.RDRAM_BASE + 0x13])
        assertEquals(0x22.toByte(), memory[AndroidRetroArchN64Backend.RDRAM_BASE + 0x12])
        assertEquals(0x33.toByte(), memory[AndroidRetroArchN64Backend.RDRAM_BASE + 0x11])
        assertEquals(0x44.toByte(), memory[AndroidRetroArchN64Backend.RDRAM_BASE + 0x10])
    }

    @Test
    fun `snapshot caches RDRAM pages for dense legacy reads`() {
        val memory = FakeCoreMemoryAccess(byteXorMask = 0)
        val backend = AndroidRetroArchN64Backend(memory)

        backend.beginSnapshot()
        backend.readRdram(0x100, 4)
        backend.readRdram(0x108, 4)
        backend.endSnapshot()

        assertEquals(
            1,
            memory.reads.count {
                it.first in AndroidRetroArchN64Backend.RDRAM_BASE until AndroidRetroArchN64Backend.ROM_BASE
            },
        )
    }

    private class FakeCoreMemoryAccess(byteXorMask: Int) : RetroArchCoreMemoryAccess {
        private val bytes = mutableMapOf<Long, Byte>()
        val reads = mutableListOf<Pair<Long, Int>>()

        init {
            putLogical(
                AndroidRetroArchN64Backend.ROM_BASE,
                0,
                byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40),
                byteXorMask,
            )
        }

        override fun read(address: Long, length: Int): ByteArray {
            reads += address to length
            return ByteArray(length) { bytes[address + it] ?: 0 }
        }

        override fun write(address: Long, data: ByteArray) {
            data.forEachIndexed { index, value -> bytes[address + index] = value }
        }

        operator fun get(address: Long): Byte = bytes[address] ?: 0

        fun putLogical(base: Long, offset: Long, value: ByteArray, mask: Int) {
            value.forEachIndexed { index, byte ->
                bytes[base + ((offset + index) xor mask.toLong())] = byte
            }
        }
    }
}
