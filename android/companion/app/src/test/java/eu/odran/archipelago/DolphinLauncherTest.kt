package eu.odran.archipelago

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolphinLauncherTest {
    @Test
    fun recognizesDolphinDiscImagesCaseInsensitively() {
        assertTrue(DolphinLauncher.isSupportedDiscName("Metroid Prime.iso"))
        assertTrue(DolphinLauncher.isSupportedDiscName("game.RVZ"))
        assertTrue(DolphinLauncher.isSupportedDiscName("disc.gcz"))
        assertFalse(DolphinLauncher.isSupportedDiscName("game.gba"))
        assertFalse(DolphinLauncher.isSupportedDiscName("notes.iso.txt"))
    }
}
