package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Test

class SniTransportPreferenceTest {
    @Test
    fun `network commands are the default SNES transport`() {
        assertEquals(
            listOf(
                SniTransportKind.RETROARCH_NETWORK_COMMANDS,
                SniTransportKind.SNES9X_BRIDGE,
            ),
            preferredSniTransportOrder(),
        )
    }

    @Test
    fun `snes9x bridge is tried first after sustained network command failure`() {
        assertEquals(
            listOf(
                SniTransportKind.SNES9X_BRIDGE,
                SniTransportKind.RETROARCH_NETWORK_COMMANDS,
            ),
            preferredSniTransportOrder(preferSnesFallback = true),
        )
    }

    @Test
    fun `direct SNES launch selects a Network Commands compatible core`() {
        assertEquals(
            "bsnes_mercury_performance_libretro_android.so",
            RetroArchLauncher.coreFileNameFor(snes = true),
        )
    }

    @Test
    fun `direct N64 launch selects Mupen64Plus Next`() {
        assertEquals(
            "mupen64plus_next_gles3_libretro_android.so",
            RetroArchLauncher.coreFileNameFor("Paper Mario Player1.z64"),
        )
    }
}
