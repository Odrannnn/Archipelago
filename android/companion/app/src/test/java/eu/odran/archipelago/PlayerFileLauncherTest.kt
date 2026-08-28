package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PlayerFileLauncherTest {
    @Test
    fun recognizesLadxHdPlayerFilesCaseInsensitively() {
        val handler = PlayerFileLauncher.handlerFor("AP_Seed_P1_Player1.APLADXHD")

        assertEquals("Links Awakening DX HD", handler?.gameName)
        assertEquals("com.zelda.ladxhd.archipelago", handler?.packageName)
        assertEquals("application/x-apladxhd", handler?.mimeType)
        assertEquals("Import into LADXHD", PlayerFileLauncher.actionLabel("seed.apladxhd"))
        assertTrue(PlayerFileLauncher.supports("seed.apladxhd"))
    }

    @Test
    fun recognizesMinishCapAndroidPlayerFiles() {
        val handler = PlayerFileLauncher.handlerFor("AP_Seed_P1_Link.APTMC")

        assertEquals("The Minish Cap", handler?.gameName)
        assertEquals("dev.picori.tmc", handler?.packageName)
        assertEquals("Launch in The Minish Cap Android", PlayerFileLauncher.actionLabel("seed.aptmc"))
        assertTrue(PlayerFileLauncher.supports("seed.aptmc"))
    }

    @Test
    fun doesNotClaimStandardSeedOrPatchFiles() {
        assertNull(PlayerFileLauncher.handlerFor("seed.zip"))
        assertFalse(PlayerFileLauncher.supports("player.apmc"))
    }

    @Test
    fun readsEmbeddedLadxHdPlayerName() {
        val file = File.createTempFile("player", ".apladxhd")
        try {
            file.writeText("""{"game":"Links Awakening DX HD","slot_name":"Marin"}""")
            assertEquals("Marin", PlayerFileLauncher.embeddedPlayerName(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsAndValidatesEmbeddedPlayerGame() {
        val valid = """{"game":"Links Awakening DX HD","slot_name":"Marin"}""".toByteArray()
        assertEquals(
            "Links Awakening DX HD",
            PlayerFileLauncher.declaredGame("seed.apladxhd", valid),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PlayerFileLauncher.declaredGame(
                "seed.apladxhd",
                """{"game":"Links Awakening DX"}""".toByteArray(),
            )
        }
    }

    @Test
    fun readsEmbeddedMinishCapPlayerName() {
        val file = File.createTempFile("player", ".aptmc")
        try {
            ZipOutputStream(file.outputStream()).use { archive ->
                archive.putNextEntry(ZipEntry("archipelago.json"))
                archive.write("""{"game":"The Minish Cap","player":2,"player_name":"Ezlo"}""".toByteArray())
                archive.closeEntry()
            }
            assertEquals("Ezlo", PlayerFileLauncher.embeddedPlayerName(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsAndValidatesMinishCapPlayerGame() {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            archive.putNextEntry(ZipEntry("archipelago.json"))
            archive.write("""{"game":"The Minish Cap","player":1,"player_name":"Link"}""".toByteArray())
            archive.closeEntry()
        }
        assertEquals(
            "The Minish Cap",
            PlayerFileLauncher.declaredGame("seed.aptmc", output.toByteArray()),
        )
    }

    @Test
    fun normalizesRoomAddressForLadxHdExtra() {
        assertEquals(
            "archipelago.gg:45678",
            PlayerFileLauncher.normalizedServerAddress("wss://archipelago.gg:45678/room"),
        )
    }

    @Test
    fun splitsHostedRoomAddressForNativeArchipelagoIntent() {
        assertEquals(
            PlayerFileServerTarget("archipelago.gg", 38281, true),
            PlayerFileLauncher.serverTarget("archipelago.gg:38281"),
        )
        assertEquals(
            PlayerFileServerTarget("192.168.1.50", 38281, false),
            PlayerFileLauncher.serverTarget("ws://192.168.1.50:38281/room"),
        )
    }
}
