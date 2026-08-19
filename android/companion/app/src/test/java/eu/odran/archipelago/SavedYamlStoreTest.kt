package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedYamlStoreTest {
    @Test
    fun savedYamlEntriesAreNewestFirstWithStableIdOrdering() {
        val older = entry("200_aaaaaaaa", 200)
        val newestB = entry("300_bbbbbbbb", 300)
        val newestA = entry("300_aaaaaaaa", 300)

        assertEquals(
            listOf(newestA, newestB, older),
            orderedSavedYamlEntries(listOf(older, newestB, newestA)),
        )
    }

    private fun entry(id: String, createdAt: Long) = SavedYamlEntry(
        id = id,
        name = id,
        createdAt = createdAt,
        byteCount = 100,
    )
}
