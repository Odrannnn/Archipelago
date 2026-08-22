package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerFileLauncherTest {
    @Test
    fun recognizesLadxHdPlayerFilesCaseInsensitively() {
        val handler = PlayerFileLauncher.handlerFor("AP_Seed_P1_Player1.APLADXHD")

        assertEquals("Links Awakening DX HD", handler?.gameName)
        assertEquals("com.zelda.ladxhd", handler?.packageName)
        assertEquals("application/x-apladxhd", handler?.mimeType)
        assertEquals("Import into LADXHD", PlayerFileLauncher.actionLabel("seed.apladxhd"))
        assertTrue(PlayerFileLauncher.supports("seed.apladxhd"))
    }

    @Test
    fun doesNotClaimStandardSeedOrPatchFiles() {
        assertNull(PlayerFileLauncher.handlerFor("seed.zip"))
        assertFalse(PlayerFileLauncher.supports("player.apmc"))
    }
}
