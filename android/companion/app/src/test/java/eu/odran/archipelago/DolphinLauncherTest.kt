package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolphinLauncherTest {
    @Test
    fun recognizesDolphinDiscImagesCaseInsensitively() {
        assertTrue(DolphinLauncher.isSupportedDiscName("Metroid Prime.iso"))
        assertTrue(DolphinLauncher.isSupportedDiscName("game.RVZ"))
        assertTrue(DolphinLauncher.isSupportedDiscName("game.gcm"))
        assertTrue(DolphinLauncher.isSupportedDiscName("disc.gcz"))
        assertFalse(DolphinLauncher.isSupportedDiscName("game.gba"))
        assertFalse(DolphinLauncher.isSupportedDiscName("notes.iso.txt"))
    }

    @Test
    fun parsesGameCubeIdentityFromRawDiscHeader() {
        val header = ByteArray(0x20)
        "GM8E01".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[6] = 0
        header[7] = 1
        byteArrayOf(0xc2.toByte(), 0x33, 0x9f.toByte(), 0x3d).copyInto(header, 0x1c)

        assertEquals(
            GameCubeDiscIdentity("GM8E01", discNumber = 0, revision = 1),
            DolphinLauncher.parseGameCubeDiscIdentity(header),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonGameCubeHeader() {
        DolphinLauncher.parseGameCubeDiscIdentity(ByteArray(0x20))
    }

    @Test
    fun usesIsoMimeTypeForDiscSaveDocuments() {
        assertEquals("application/x-iso9660-image", DolphinLauncher.discMimeType("patched.ISO"))
        assertEquals("application/octet-stream", DolphinLauncher.discMimeType("patched.rvz"))
    }
}
