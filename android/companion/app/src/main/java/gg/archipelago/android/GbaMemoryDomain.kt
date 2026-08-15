package gg.archipelago.android

/** GBA system-bus bases corresponding to the memory domains used by APWorlds. */
enum class GbaMemoryDomain(private val base: Long) {
    EWRAM(0x02000000),
    IWRAM(0x03000000),
    ROM(0x08000000),
    SRAM(0x0E000000),
    SYSTEM_BUS(0),
    ;

    fun toBusAddress(offset: Long): Long {
        require(offset >= 0) { "Memory offset must be positive" }
        return if (this == SYSTEM_BUS) offset else base + offset
    }
}
