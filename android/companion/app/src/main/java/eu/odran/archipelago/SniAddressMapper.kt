package eu.odran.archipelago

/** Maps SNI/FX Pak Pro virtual addresses onto a libretro system memory bus. */
fun interface SniAddressMapper {
    fun toBusAddress(address: Long): Long
}

/** Standard LoROM mapping used by Super Metroid and other LoROM SNI clients. */
object LoRomSniAddressMapper : SniAddressMapper {
    override fun toBusAddress(address: Long): Long = when (address) {
        in 0xF50000 until 0x1000000 -> ((address - 0xF50000) and 0x01FFFF) + 0x7E0000
        in 0xE00000 until 0xEE0000 -> {
            val linear = address - 0xE00000
            ((0x70 + (linear shr 15)) shl 16) + (linear and 0x7FFF)
        }
        in 0xEE0000 until 0xF00000 -> {
            val linear = (address - 0xE00000) and 0x07FFFF
            ((0xF0 + (linear shr 15)) shl 16) + (linear and 0x7FFF)
        }
        in 0 until 0xE00000 -> {
            val linear = address and 0x3FFFFF
            ((0x80 + (linear shr 15)) shl 16) + ((linear and 0x7FFF) or 0x8000)
        }
        else -> error("Unmapped LoROM SNI address: 0x${address.toString(16)}")
    }
}
