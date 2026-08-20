package eu.odran.archipelago

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun archiveRoundTripPreservesFilesSettingsAndManifest() {
        val sourceRoot = temporaryFolder.newFolder("sources")
        val seed = File(sourceRoot, "seed/Players.yaml").apply {
            parentFile?.mkdirs()
            writeText("name: Player1\ngame: Super Metroid\n")
        }
        val rom = File(sourceRoot, "rom.bin").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        val preferences = "{\"version\":1,\"stores\":{}}".toByteArray()
        val output = ByteArrayOutputStream()

        val written = BackupArchive.write(
            output,
            "eu.odran.archipelago",
            "test",
            123_456L,
            listOf(
                BackupArchiveSource("data/offline_generator/seed/Players.yaml", seed),
                BackupArchiveSource("data/base_rom/rom.bin", rom),
            ),
            preferences,
        )
        val staging = temporaryFolder.newFolder("staging")
        val restored = BackupArchive.extract(
            ByteArrayInputStream(output.toByteArray()),
            staging,
            "eu.odran.archipelago",
        )

        assertEquals(123_456L, restored.manifest.createdAt)
        assertEquals(written.entries.map { it.path }, restored.manifest.entries.map { it.path })
        assertEquals(seed.readText(), File(staging, "data/offline_generator/seed/Players.yaml").readText())
        assertArrayEquals(rom.readBytes(), File(staging, "data/base_rom/rom.bin").readBytes())
        assertArrayEquals(preferences, restored.preferences)
    }

    @Test
    fun extractionRejectsPathTraversalBeforeWritingOutsideStaging() {
        val archive = zipOf("data/offline_generator/../../escaped.txt" to "bad".toByteArray())
        val staging = temporaryFolder.newFolder("unsafe-staging")

        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.extract(ByteArrayInputStream(archive), staging, "eu.odran.archipelago")
        }
        assertEquals(false, File(staging.parentFile, "escaped.txt").exists())
    }

    @Test
    fun extractionRejectsContentWhoseChecksumDoesNotMatchManifest() {
        val preferences = "settings".toByteArray()
        val data = "actual data".toByteArray()
        val manifest = JSONObject().apply {
            put("format", BackupArchive.FORMAT)
            put("format_version", BackupArchive.FORMAT_VERSION)
            put("app_id", "eu.odran.archipelago")
            put("app_version", "test")
            put("created_at", 1L)
            put("roots", JSONArray(BackupArchive.SUPPORTED_ROOTS))
            put("entries", JSONArray().apply {
                put(entry("data/saved_yamls/example.yaml", data.size.toLong(), "0".repeat(64)))
                put(entry(BackupArchive.PREFERENCES_PATH, preferences.size.toLong(), preferences.sha256()))
            })
        }.toString().toByteArray()
        val archive = zipOf(
            "data/saved_yamls/example.yaml" to data,
            BackupArchive.PREFERENCES_PATH to preferences,
            BackupArchive.MANIFEST_PATH to manifest,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.extract(
                ByteArrayInputStream(archive),
                temporaryFolder.newFolder("checksum-staging"),
                "eu.odran.archipelago",
            )
        }
        assertEquals(true, error.message.orEmpty().contains("integrity check failed"))
    }

    @Test
    fun entryValidationRejectsAbsoluteWindowsAndEmptySegments() {
        listOf(
            "/absolute/file",
            "C:/windows/file",
            "data/offline_generator/../escape",
            "data//file",
            "data\\offline_generator\\file",
        ).forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) {
                BackupArchive.validateEntryPath(path)
            }
        }
    }

    private fun entry(path: String, bytes: Long, sha256: String) = JSONObject().apply {
        put("path", path)
        put("bytes", bytes)
        put("sha256", sha256)
        put("modified_at", 0L)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
