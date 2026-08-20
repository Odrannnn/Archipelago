package eu.odran.archipelago

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentUpdatesTest {
    @Test
    fun `release parser discovers every managed component`() {
        val companion = JSONArray().put(release(
            "android-v1.0.0",
            prerelease = false,
            asset("Dolphin-Archipelago-2603-49-arm64-v8a-x86_64-release.apk", 'a', 20_000_000),
            asset("mgba_apbridge_v9_libretro_android.so", 'b', 6_000_000),
            asset("snes9x_apbridge_v1_libretro_android.so", 'c', 3_000_000),
        ))
        val popTracker = JSONArray().put(release(
            "v0.35.4-android.1",
            prerelease = true,
            asset("PopTracker-Android-0.35.4-android.1.apk", 'd', 130_000_000),
        ))

        val assets = ComponentReleaseParser.parse(companion.toString(), popTracker.toString())
            .associateBy { it.component }

        assertEquals(ManagedComponent.entries.toSet(), assets.keys)
        assertEquals("2603-49", assets.getValue(ManagedComponent.DOLPHIN).version)
        assertEquals("0.35.4-android.1", assets.getValue(ManagedComponent.POPTRACKER).version)
        assertEquals("v9", assets.getValue(ManagedComponent.MGBA_CORE).version)
        assertEquals("v1", assets.getValue(ManagedComponent.SNES9X_CORE).version)
        assertEquals("a".repeat(64), assets.getValue(ManagedComponent.DOLPHIN).sha256)
    }

    @Test
    fun `companion prereleases and malformed assets are ignored`() {
        val companion = JSONArray()
            .put(release(
                "android-v2.0.0-beta",
                prerelease = true,
                asset("Dolphin-Archipelago-9999-1-arm64-v8a-x86_64-release.apk", 'a', 10),
            ))
            .put(release(
                "android-v1.0.0",
                prerelease = false,
                asset("Dolphin-Archipelago-2603-49-arm64-v8a-x86_64-release.apk", 'b', 10),
            ))

        val result = ComponentReleaseParser.parse(companion.toString(), "[]")

        assertEquals(1, result.size)
        assertEquals("2603-49", result.single().version)
        assertEquals("b".repeat(64), result.single().sha256)
    }

    @Test
    fun `normalized catalog round trip preserves integrity metadata`() {
        val original = ComponentAsset(
            ManagedComponent.MGBA_CORE,
            "v9",
            "mgba_apbridge_v9_libretro_android.so",
            "https://github.com/example/release/core.so",
            "e".repeat(64),
            1234,
            "android-v1",
            "2026-01-01T00:00:00Z",
        )

        assertEquals(listOf(original), ComponentReleaseParser.decode(ComponentReleaseParser.encode(listOf(original))))
    }

    @Test
    fun `component version comparison handles custom Android versions`() {
        assertTrue(ComponentVersion.isNewer("0.35.4-android.2", "0.35.4-android.1"))
        assertTrue(ComponentVersion.isNewer("2603-50", "2603-49"))
        assertTrue(ComponentVersion.isNewer("v10", "v9"))
        assertFalse(ComponentVersion.isNewer("0.35.4-android.1", "0.35.4-android.1"))
        assertFalse(ComponentVersion.isNewer("2603-49", "2603-50"))
    }

    @Test
    fun `same core version with different digest is not called an update`() {
        val installed = InstalledCoreState(
            installedFileName = "mgba_apbridge_v9_libretro_android.so",
            installedVersion = "v9",
            installedSha256 = "f".repeat(64),
            sameVersionAsAvailable = true,
            matchesAvailable = false,
        )

        assertEquals(
            CoreReleaseRelation.CURRENT_VERSION_DIFFERENT_BUILD,
            installed.relationTo("v9"),
        )
    }

    @Test
    fun `older and verified core relations remain distinct`() {
        assertEquals(
            CoreReleaseRelation.UPDATE_AVAILABLE,
            InstalledCoreState(
                installedFileName = "mgba_apbridge_v8_libretro_android.so",
                installedVersion = "v8",
            ).relationTo("v9"),
        )
        assertEquals(
            CoreReleaseRelation.VERIFIED_CURRENT,
            InstalledCoreState(
                installedFileName = "mgba_apbridge_v9_libretro_android.so",
                installedVersion = "v9",
                sameVersionAsAvailable = true,
                matchesAvailable = true,
            ).relationTo("v9"),
        )
    }

    private fun release(
        tag: String,
        prerelease: Boolean,
        vararg assets: JSONObject,
    ) = JSONObject()
        .put("tag_name", tag)
        .put("published_at", "2026-01-01T00:00:00Z")
        .put("draft", false)
        .put("prerelease", prerelease)
        .put("assets", JSONArray().apply { assets.forEach(::put) })

    private fun asset(name: String, digestCharacter: Char, size: Long) = JSONObject()
        .put("name", name)
        .put("browser_download_url", "https://github.com/example/releases/download/v1/$name")
        .put("digest", "sha256:${digestCharacter.toString().repeat(64)}")
        .put("size", size)
}
