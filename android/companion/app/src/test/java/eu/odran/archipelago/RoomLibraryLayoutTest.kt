package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomLibraryLayoutTest {
    @Test
    fun `empty room library stays compact`() {
        assertEquals(88, roomLibraryHeightDp(roomCount = 0, screenHeightDp = 800))
    }

    @Test
    fun `one room receives a useful minimum height`() {
        assertEquals(320, roomLibraryHeightDp(roomCount = 1, screenHeightDp = 800))
    }

    @Test
    fun `long room libraries are capped to the available screen`() {
        assertEquals(464, roomLibraryHeightDp(roomCount = 20, screenHeightDp = 800))
        assertEquals(680, roomLibraryHeightDp(roomCount = 20, screenHeightDp = 1_400))
    }
}
