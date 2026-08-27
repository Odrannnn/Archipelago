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
            asset("Archipelago-Companion-1.0.0-arm64-v8a-release.apk", 'e', 40_000_000),
            asset("Dolphin-Archipelago-2603-49-arm64-v8a-x86_64-release.apk", 'a', 20_000_000),
            asset("mgba_apbridge_v9_libretro_android.so", 'b', 6_000_000),
            asset("snes9x_apbridge_v1_libretro_android.so", 'c', 3_000_000),
        ))
        val popTracker = JSONArray().put(release(
            "v0.35.4-android.1",
            prerelease = true,
            asset("PopTracker-Android-0.35.4-android.1.apk", 'd', 130_000_000),
        ))
        val ladxhd = JSONArray().put(release(
            "archipelago-v2.0.5-ap1",
            prerelease = false,
            asset("LADXHD-Archipelago-v2.0.5-ap1.apk", 'f', 45_000_000),
        ))

        val assets = ComponentReleaseParser.parse(
            companion.toString(),
            popTracker.toString(),
            ladxhd.toString(),
        )
            .associateBy { it.component }

        assertEquals(ManagedComponent.entries.toSet(), assets.keys)
        assertEquals("1.0.0", assets.getValue(ManagedComponent.COMPANION).version)
        assertEquals("2603-49", assets.getValue(ManagedComponent.DOLPHIN).version)
        assertEquals("0.35.4-android.1", assets.getValue(ManagedComponent.POPTRACKER).version)
        assertEquals("v9", assets.getValue(ManagedComponent.MGBA_CORE).version)
        assertEquals("v1", assets.getValue(ManagedComponent.SNES9X_CORE).version)
        assertEquals("2.0.5", assets.getValue(ManagedComponent.LADXHD_ARCHIPELAGO).version)
        assertEquals(
            ComponentSection.EXTRA_PROJECTS,
            assets.getValue(ManagedComponent.LADXHD_ARCHIPELAGO).component.section,
        )
        assertEquals(
            "https://github.com/Odrannnn/LADXHD-Archipelago",
            ManagedComponent.LADXHD_ARCHIPELAGO.projectUrl,
        )
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

        val result = ComponentReleaseParser.parse(companion.toString(), "[]", "[]")

        assertEquals(1, result.size)
        assertEquals("2603-49", result.single().version)
        assertEquals("b".repeat(64), result.single().sha256)
    }

    @Test
    fun `release parser selects newest semantic version when github order is lexical`() {
        val companion = JSONArray()
            .put(release(
                "android-v0.38.9",
                prerelease = false,
                asset("Archipelago-Companion-0.38.9-arm64-v8a-release.apk", 'a', 10),
            ))
            .put(release(
                "android-v0.38.10",
                prerelease = false,
                asset("Archipelago-Companion-0.38.10-arm64-v8a-release.apk", 'b', 10),
            ))

        val result = ComponentReleaseParser.parse(companion.toString(), "[]", "[]")

        assertEquals("0.38.10", result.single().version)
        assertEquals("android-v0.38.10", result.single().releaseTag)
    }

    @Test
    fun `release parser prefers most recently published rebuild of same version`() {
        val companion = JSONArray()
            .put(release(
                "android-v1.0.0-old",
                prerelease = false,
                asset("Archipelago-Companion-1.0.0-arm64-v8a-release.apk", 'a', 10),
                publishedAt = "2026-01-01T00:00:00Z",
            ))
            .put(release(
                "android-v1.0.0-new",
                prerelease = false,
                asset("Archipelago-Companion-1.0.0-arm64-v8a-release.apk", 'b', 10),
                publishedAt = "2026-01-02T00:00:00Z",
            ))

        val result = ComponentReleaseParser.parse(companion.toString(), "[]", "[]")

        assertEquals("android-v1.0.0-new", result.single().releaseTag)
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

    @Test
    fun `notification resolver includes only newer installed components`() {
        val companion = componentAsset(ManagedComponent.COMPANION, "2.0.0")
        val dolphin = componentAsset(ManagedComponent.DOLPHIN, "2603-50")
        val popTracker = componentAsset(ManagedComponent.POPTRACKER, "0.36.0")
        val mgba = componentAsset(ManagedComponent.MGBA_CORE, "v10")
        val snes9x = componentAsset(ManagedComponent.SNES9X_CORE, "v2")
        val ladxhd = componentAsset(ManagedComponent.LADXHD_ARCHIPELAGO, "2.0.5")

        val updates = ComponentUpdateResolver.resolve(
            listOf(companion, dolphin, popTracker, mgba, snes9x, ladxhd),
            apkStates = mapOf(
                ManagedComponent.COMPANION to InstalledApkState("companion", "1.0.0", 1),
                ManagedComponent.DOLPHIN to InstalledApkState("dolphin", "2603-50", 2),
                // An optional component which is not installed is not an update notification.
                ManagedComponent.POPTRACKER to null,
                ManagedComponent.LADXHD_ARCHIPELAGO to InstalledApkState(
                    "com.zelda.ladxhd.archipelago", "2.0.4", 204,
                ),
            ),
            coreStates = mapOf(
                ManagedComponent.MGBA_CORE to InstalledCoreState(
                    installedFileName = "mgba_apbridge_v9_libretro_android.so",
                    installedVersion = "v9",
                ),
                ManagedComponent.SNES9X_CORE to InstalledCoreState(
                    installedFileName = "snes9x_apbridge_v2_libretro_android.so",
                    installedVersion = "v2",
                    sameVersionAsAvailable = true,
                    matchesAvailable = false,
                ),
            ),
        )

        assertEquals(
            listOf(
                ManagedComponent.COMPANION,
                ManagedComponent.MGBA_CORE,
                ManagedComponent.LADXHD_ARCHIPELAGO,
            ),
            updates.map { it.component },
        )
    }

    private fun componentAsset(component: ManagedComponent, version: String) = ComponentAsset(
        component,
        version,
        "${component.componentId}-$version",
        "https://github.com/example/releases/download/v1/${component.componentId}",
        "a".repeat(64),
        100,
        "v1",
        "2026-01-01T00:00:00Z",
    )

    private fun release(
        tag: String,
        prerelease: Boolean,
        vararg assets: JSONObject,
        publishedAt: String = "2026-01-01T00:00:00Z",
    ) = JSONObject()
        .put("tag_name", tag)
        .put("published_at", publishedAt)
        .put("draft", false)
        .put("prerelease", prerelease)
        .put("assets", JSONArray().apply { assets.forEach(::put) })

    private fun asset(name: String, digestCharacter: Char, size: Long) = JSONObject()
        .put("name", name)
        .put("browser_download_url", "https://github.com/example/releases/download/v1/$name")
        .put("digest", "sha256:${digestCharacter.toString().repeat(64)}")
        .put("size", size)
}
