package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedRoomCardPresentationTest {
    @Test
    fun `inactive room asks for a player before activation when needed`() {
        val presentation = primary(isActive = false, playerSelected = false, playerChoiceCount = 3)

        assertEquals(RoomPrimaryAction.ACTIVATE, presentation.action)
        assertEquals("Choose player & activate", presentation.label)
        assertTrue(presentation.enabled)
    }

    @Test
    fun `inactive room with a selected player can activate directly`() {
        val presentation = primary(isActive = false, playerSelected = true, playerChoiceCount = 3)

        assertEquals(RoomPrimaryAction.ACTIVATE, presentation.action)
        assertEquals("Make active", presentation.label)
    }

    @Test
    fun `active sleeping and failed rooms expose recovery actions`() {
        val sleeping = primary(port = 0, runtimeState = RoomRuntimeState.SLEEPING)
        val failed = primary(port = -1, runtimeState = RoomRuntimeState.ERROR)
        val unavailable = primary(port = 38_281, runtimeState = RoomRuntimeState.UNAVAILABLE)

        assertEquals(RoomPrimaryAction.WAKE, sleeping.action)
        assertEquals("Wake & refresh", sleeping.label)
        assertEquals(RoomPrimaryAction.RETRY, failed.action)
        assertEquals("Retry room", failed.label)
        assertEquals(RoomPrimaryAction.RETRY, unavailable.action)
    }

    @Test
    fun `transient room work disables the primary action`() {
        val waking = primary(port = 0, runtimeState = RoomRuntimeState.WAKING)
        val refreshing = primary(runtimeState = RoomRuntimeState.REFRESHING)

        assertEquals(RoomPrimaryAction.WAIT, waking.action)
        assertEquals("Waking room…", waking.label)
        assertFalse(waking.enabled)
        assertEquals(RoomPrimaryAction.WAIT, refreshing.action)
        assertEquals("Refreshing…", refreshing.label)
        assertFalse(refreshing.enabled)
    }

    @Test
    fun `active room prioritizes player choice before launch actions`() {
        val presentation = primary(
            playerSelected = false,
            playerChoiceCount = 2,
            sohPlayerCount = 1,
            nativePlayerFileNames = listOf("Player.apladxhd"),
        )

        assertEquals(RoomPrimaryAction.CHOOSE_PLAYER, presentation.action)
        assertEquals("Choose player", presentation.label)
    }

    @Test
    fun `active room exposes supported launch actions`() {
        val soh = primary(playerSelected = true, sohPlayerCount = 1)
        val native = primary(
            playerSelected = true,
            nativePlayerFileNames = listOf("Player.apladxhd"),
        )

        assertEquals(RoomPrimaryAction.LAUNCH_SOH, soh.action)
        assertEquals("Launch Ship of Harkinian", soh.label)
        assertEquals(RoomPrimaryAction.OPEN_PLAYER_FILE, native.action)
        assertEquals("Import into LADXHD", native.label)
    }

    @Test
    fun `sharing is disabled only when neither invite path exists`() {
        val unavailable = roomSharePresentation(false, patchlessChoiceCount = 0)
        val patchless = roomSharePresentation(false, patchlessChoiceCount = 1)
        val unknownLinkedSeed = roomSharePresentation(null, patchlessChoiceCount = 0)

        assertFalse(unavailable.enabled)
        assertEquals("Share unavailable", unavailable.label)
        assertTrue(patchless.enabled)
        assertTrue(unknownLinkedSeed.enabled)
    }

    private fun primary(
        isActive: Boolean = true,
        port: Int = 38_281,
        runtimeState: RoomRuntimeState = RoomRuntimeState.RUNNING,
        playerSelected: Boolean = true,
        playerChoiceCount: Int = 0,
        sohPlayerCount: Int = 0,
        nativePlayerFileNames: List<String> = emptyList(),
    ) = roomPrimaryPresentation(
        isActive,
        port,
        runtimeState,
        playerSelected,
        playerChoiceCount,
        sohPlayerCount,
        nativePlayerFileNames,
    )
}
