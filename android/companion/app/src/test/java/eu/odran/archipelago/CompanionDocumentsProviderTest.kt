package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionDocumentsProviderTest {
    @Test
    fun generatedFileDocumentIdsRoundTripUnicodeNames() {
        val expected = SeedFileDocumentId(
            seedHistoryId = "1724000000000_a1b2c3d4",
            fileName = "Player 1 – Link's Awakening.yaml",
        )

        assertEquals(
            expected,
            parseSeedFileDocumentId(seedFileDocumentId(expected.seedHistoryId, expected.fileName)),
        )
    }

    @Test
    fun malformedGeneratedFileDocumentIdsAreRejected() {
        assertNull(parseSeedFileDocumentId("saved-yaml:1724000000000_a1b2c3d4"))
        assertNull(parseSeedFileDocumentId("seed-file:invalid:UGxheWVycy55YW1s"))
        assertNull(parseSeedFileDocumentId("seed-file:1724000000000_a1b2c3d4:not-base64!"))
    }

    @Test
    fun exportedNamesAndMimeTypesRemainFileManagerFriendly() {
        assertEquals("My settings.yaml", yamlDisplayName("My settings"))
        assertEquals("Existing.yml", yamlDisplayName("Existing.yml"))
        assertEquals("Player_One.yaml", yamlDisplayName("Player/One"))
        assertEquals("application/yaml", mimeType("Players.yaml"))
        assertEquals("application/zip", mimeType("AP_Seed.zip"))
        assertEquals("application/octet-stream", mimeType("Player1.apsm"))
    }
}
