package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolphinIniManagerTest {
    @Test
    fun disablesGdbUsingDolphinsUpstreamSentinel() {
        val original = "[General]\nGDBPort = 55020\n"

        val update = DolphinIniManager.updateGdbPort(
            original,
            DolphinIniManager.DISABLED_GDB_PORT,
        )

        assertEquals(55020, update.previousPort)
        assertEquals("[General]\nGDBPort = -1\n", update.content)
    }

    @Test
    fun replacesExistingPortAndPreservesFormattingAndComment() {
        val original = "[General]\r\nGDBPort = -1 ; debugger\r\n[Core]\r\nCPUThread = True\r\n"

        val update = DolphinIniManager.updateGdbPort(original, 55020)

        assertEquals(-1, update.previousPort)
        assertEquals(
            "[General]\r\nGDBPort = 55020 ; debugger\r\n[Core]\r\nCPUThread = True\r\n",
            update.content,
        )
    }

    @Test
    fun insertsPortIntoExistingGeneralSection() {
        val original = "[General]\nShowLag = False\n\n[Core]\nCPUThread = True\n"

        val update = DolphinIniManager.updateGdbPort(original, 55020)

        assertEquals(null, update.previousPort)
        assertEquals(
            "[General]\nShowLag = False\n\nGDBPort = 55020\n[Core]\nCPUThread = True\n",
            update.content,
        )
    }

    @Test
    fun appendsGeneralSectionOnlyWhenMissing() {
        val update = DolphinIniManager.updateGdbPort("[Core]\nCPUThread = True", 55020)

        assertEquals(
            "[Core]\nCPUThread = True\n\n[General]\nGDBPort = 55020",
            update.content,
        )
        assertEquals(1, Regex("\\[General]", RegexOption.IGNORE_CASE).findAll(update.content).count())
    }

    @Test
    fun removesDuplicatePortsOnlyInsideGeneralSections() {
        val original = """
            [General]
            GDBPort = -1
            GDBPort=1234
            [Other]
            GDBPort = 42
        """.trimIndent()

        val update = DolphinIniManager.updateGdbPort(original, 55020)

        assertTrue(update.content.contains("GDBPort = 55020"))
        assertTrue(update.content.contains("[Other]\nGDBPort = 42"))
        assertFalse(update.content.contains("GDBPort=1234"))
    }

    @Test
    fun unchangedPortDoesNotRewriteContent() {
        val original = "[General]\nGDBPort = 55020\n"

        val update = DolphinIniManager.updateGdbPort(original, 55020)

        assertEquals(55020, update.previousPort)
        assertEquals(original, update.content)
    }
}
