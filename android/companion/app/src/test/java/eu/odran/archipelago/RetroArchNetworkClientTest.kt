package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
