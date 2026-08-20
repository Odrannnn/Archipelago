package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ClientConsoleStoreTest {
    @Before
    fun reset() {
        ClientConsoleStore.clear()
        while (ClientConsoleStore.pollCommand() != null) Unit
    }

    @Test
    fun submittedCommandsAppearInTranscriptAndInbox() {
        ClientConsoleStore.submit("  /help  ")

        assertEquals("/help", ClientConsoleStore.pollCommand())
        assertNull(ClientConsoleStore.pollCommand())
        assertEquals("input", ClientConsoleStore.snapshot().entries.single().kind)
        assertEquals("/help", ClientConsoleStore.snapshot().entries.single().text)
    }

    @Test
    fun multiLineOutputStaysInOneConsoleBubble() {
        ClientConsoleStore.append("output", "one\ntwo")

        assertEquals(listOf("one\ntwo"), ClientConsoleStore.snapshot().entries.map { it.text })
    }
}
