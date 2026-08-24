package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerFileLauncherTest {
    @Test
    fun recognizesLadxHdPlayerFilesCaseInsensitively() {
        val handler = PlayerFileLauncher.handlerFor("AP_Seed_P1_Player1.APLADXHD")

        assertEquals("Links Awakening DX HD", handler?.gameName)
        assertEquals("com.zelda.ladxhd.archipelago", handler?.packageName)
        assertEquals(listOf("com.zelda.ladxhd"), handler?.alternatePackageNames)
        assertEquals("application/x-apladxhd", handler?.mimeType)
        assertEquals("Import into LADXHD", PlayerFileLauncher.actionLabel("seed.apladxhd"))
        assertTrue(PlayerFileLauncher.supports("seed.apladxhd"))
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
    fun normalizesRoomAddressForLadxHdExtra() {
        assertEquals(
            "archipelago.gg:45678",
            PlayerFileLauncher.normalizedServerAddress("wss://archipelago.gg:45678/room"),
        )
    }
}
