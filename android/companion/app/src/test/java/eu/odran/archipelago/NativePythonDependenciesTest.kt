package eu.odran.archipelago

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePythonDependenciesTest {
    @Test
    fun `catalog accepts only release assets matching curated integrity metadata`() {
        val digest = "a".repeat(64)
        val release = release(
            asset("py_randomprime-1.31.1-cp310-abi3-android_26_arm64_v8a.zip", digest, 4_000_000),
        )
        val index = index(digest, 4_000_000)

        val dependency = NativeDependencyCatalogParser.parse(release.toString(), index.toString()).single()

        assertEquals("py-randomprime", dependency.packageName)
        assertEquals("py_randomprime", dependency.moduleName)
        assertEquals("1.31.1", dependency.version)
        assertEquals("Metroid Prime", dependency.worlds.single().game)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `catalog rejects a digest which differs from the GitHub release`() {
        NativeDependencyCatalogParser.parse(
            release(asset(
                "py_randomprime-1.31.1-cp310-abi3-android_26_arm64_v8a.zip",
                "b".repeat(64),
                4_000_000,
            )).toString(),
            index("a".repeat(64), 4_000_000).toString(),
        )
    }

    @Test
    fun `world rules constrain dependency to reviewed APWorld versions`() {
        val rule = NativeDependencyWorld("Metroid Prime", "0.5.4", "0.5.4")
        val matching = world("0.5.4")
        val newer = world("0.5.5")

        assertTrue(rule.matches(matching))
        assertFalse(rule.matches(newer))
    }

    private fun world(version: String) = ImportedApWorld(
        packageName = "metroidprime",
        game = "Metroid Prime",
        worldVersion = version,
        minimumApVersion = null,
        maximumApVersion = null,
        sourceName = "metroidprime.apworld",
        sha256 = "c".repeat(64),
        installedAt = 0,
    )

    private fun release(vararg assets: JSONObject) = JSONObject()
        .put("tag_name", NativeDependencyCatalogClient.RELEASE_TAG)
        .put("assets", JSONArray().apply { assets.forEach(::put) })

    private fun asset(name: String, digest: String, bytes: Long) = JSONObject()
        .put("name", name)
        .put("digest", "sha256:$digest")
        .put("size", bytes)
        .put("browser_download_url", "https://github.com/Odrannnn/Archipelago/releases/download/android-python-dependencies/$name")

    private fun index(digest: String, bytes: Long) = JSONObject()
        .put("schema", 1)
        .put("packages", JSONArray().put(JSONObject()
            .put("package", "py-randomprime")
            .put("version", "1.31.1")
            .put("module", "py_randomprime")
            .put("python_abi", "abi3")
            .put("android_abi", "arm64-v8a")
            .put("minimum_sdk", 26)
            .put("file_name", "py_randomprime-1.31.1-cp310-abi3-android_26_arm64_v8a.zip")
            .put("sha256", digest)
            .put("byte_count", bytes)
            .put("source_url", "https://files.pythonhosted.org/py_randomprime-1.31.1.tar.gz")
            .put("source_sha256", "d".repeat(64))
            .put("worlds", JSONArray().put(JSONObject()
                .put("game", "Metroid Prime")
                .put("minimum_world_version", "0.5.4")
                .put("maximum_world_version", "0.5.4")))))
}
