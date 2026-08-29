package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveLayoutPolicyTest {
    @Test
    fun `window width classes use live content breakpoints`() {
        assertEquals(CompanionWindowWidthClass.COMPACT, companionWindowWidthClass(599))
        assertEquals(CompanionWindowWidthClass.MEDIUM, companionWindowWidthClass(600))
        assertEquals(CompanionWindowWidthClass.MEDIUM, companionWindowWidthClass(839))
        assertEquals(CompanionWindowWidthClass.EXPANDED, companionWindowWidthClass(840))
    }

    @Test
    fun `expanded windows split only when there are multiple cards`() {
        assertFalse(shouldUseSplitLayout(widthDp = 839, cardCount = 4))
        assertFalse(shouldUseSplitLayout(widthDp = 1_200, cardCount = 1))
        assertTrue(shouldUseSplitLayout(widthDp = 1_200, cardCount = 2))
    }

    @Test
    fun `action rows stack before their controls become cramped`() {
        assertFalse(shouldLayOutActionsHorizontally(300, 2, 156, 6))
        assertTrue(shouldLayOutActionsHorizontally(318, 2, 156, 6))
        assertTrue(shouldLayOutActionsHorizontally(276, 3, 88, 6))
        assertTrue(shouldLayOutActionsHorizontally(120, 1, 156, 6))
    }
}
