package eu.odran.archipelago

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal enum class ManagedComponent(
    val componentId: String,
    val displayName: String,
    val kind: ComponentKind,
    val assetPattern: Regex,
    val packageName: String? = null,
    val alternatePackageName: String? = null,
    val coreFamilyPattern: Regex? = null,
    val section: ComponentSection = ComponentSection.COMPANION_COMPONENTS,
    val projectUrl: String? = null,
) {
    COMPANION(
        "companion",
        "Archipelago Companion",
        ComponentKind.APK,
        Regex("^Archipelago-Companion-(.+)-arm64-v8a-release\\.apk$"),
        "eu.odran.archipelago",
    ),
    DOLPHIN(
        "dolphin",
        "Dolphin Archipelago",
        ComponentKind.APK,
        Regex("^Dolphin-Archipelago-(.+)-arm64-v8a-x86_64-release\\.apk$"),
        "eu.odran.dolphin.archipelago",
        "eu.odran.dolphin.archipelago.debug",
    ),
    POPTRACKER(
        "poptracker",
        "PopTracker Android",
        ComponentKind.APK,
        Regex("^PopTracker-Android-(.+)\\.apk$"),
        "io.github.poptracker.android",
    ),
    MGBA_CORE(
        "mgba-core",
        "mGBA Archipelago core",
        ComponentKind.CORE,
        Regex("^mgba_apbridge_(v[0-9]+)_libretro_android\\.so$"),
        coreFamilyPattern = Regex("^mgba_apbridge_v[0-9]+_libretro_android\\.so$"),
    ),
    SNES9X_CORE(
        "snes9x-core",
        "SNES9x Archipelago core",
        ComponentKind.CORE,
        Regex("^snes9x_apbridge_(v[0-9]+)_libretro_android\\.so$"),
        coreFamilyPattern = Regex("^snes9x_apbridge_v[0-9]+_libretro_android\\.so$"),
    ),
    LADXHD_ARCHIPELAGO(
        "ladxhd-archipelago",
        "LADXHD Archipelago",
        ComponentKind.APK,
        Regex("^LADXHD-Archipelago-v?([0-9]+(?:\\.[0-9]+){2})(?:-ap[0-9]+)?\\.apk$"),
        "com.zelda.ladxhd.archipelago",
        "com.zelda.ladxhd",
        section = ComponentSection.EXTRA_PROJECTS,
        projectUrl = "https://github.com/Odrannnn/LADXHD-Archipelago",
    );

    fun versionFrom(fileName: String): String? = assetPattern.matchEntire(fileName)?.groupValues?.get(1)

    companion object {
        fun fromId(id: String): ManagedComponent? = entries.firstOrNull { it.componentId == id }
    }
}

internal enum class ComponentKind { APK, CORE }

internal enum class ComponentSection { COMPANION_COMPONENTS, EXTRA_PROJECTS }

internal data class ComponentAsset(
    val component: ManagedComponent,
    val version: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val byteCount: Long,
    val releaseTag: String,
    val publishedAt: String,
) {
    fun toJson() = JSONObject()
        .put("component", component.componentId)
        .put("version", version)
        .put("file_name", fileName)
        .put("download_url", downloadUrl)
        .put("sha256", sha256)
        .put("byte_count", byteCount)
        .put("release_tag", releaseTag)
        .put("published_at", publishedAt)

    companion object {
        fun fromJson(json: JSONObject): ComponentAsset? {
            val component = ManagedComponent.fromId(json.optString("component")) ?: return null
            val fileName = json.optString("file_name")
            val version = json.optString("version")
            val downloadUrl = json.optString("download_url")
            val sha256 = json.optString("sha256").lowercase()
            val byteCount = json.optLong("byte_count", -1)
            if (fileName.isBlank() || version.isBlank() || downloadUrl.isBlank() ||
                !sha256.matches(Regex("[0-9a-f]{64}")) || byteCount <= 0
            ) return null
            return ComponentAsset(
                component,
                version,
                fileName,
                downloadUrl,
                sha256,
                byteCount,
                json.optString("release_tag"),
                json.optString("published_at"),
            )
        }
    }
}

internal object ComponentReleaseParser {
    fun parse(
        companionReleases: String,
        popTrackerReleases: String,
        ladxhdReleases: String,
    ): List<ComponentAsset> = buildList {
        val companion = JSONArray(companionReleases)
        addFirstMatch(companion, ManagedComponent.COMPANION, allowPrerelease = false)?.let(::add)
        addFirstMatch(companion, ManagedComponent.DOLPHIN, allowPrerelease = false)?.let(::add)
        addFirstMatch(companion, ManagedComponent.MGBA_CORE, allowPrerelease = false)?.let(::add)
        addFirstMatch(companion, ManagedComponent.SNES9X_CORE, allowPrerelease = false)?.let(::add)
        addFirstMatch(
            JSONArray(popTrackerReleases),
            ManagedComponent.POPTRACKER,
            allowPrerelease = true,
        )?.let(::add)
        addFirstMatch(
            JSONArray(ladxhdReleases),
            ManagedComponent.LADXHD_ARCHIPELAGO,
            allowPrerelease = false,
        )?.let(::add)
    }

    private fun addFirstMatch(
        releases: JSONArray,
        component: ManagedComponent,
        allowPrerelease: Boolean,
    ): ComponentAsset? {
        repeat(releases.length()) { releaseIndex ->
            val release = releases.optJSONObject(releaseIndex) ?: return@repeat
            if (release.optBoolean("draft") || (!allowPrerelease && release.optBoolean("prerelease"))) {
                return@repeat
            }
            val assets = release.optJSONArray("assets") ?: return@repeat
            repeat(assets.length()) { assetIndex ->
                val asset = assets.optJSONObject(assetIndex) ?: return@repeat
                val name = asset.optString("name")
                val version = component.versionFrom(name) ?: return@repeat
                val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
                val url = asset.optString("browser_download_url")
                val size = asset.optLong("size", -1)
                if (!digest.matches(Regex("[0-9a-f]{64}")) ||
                    !url.startsWith("https://github.com/") || size <= 0
                ) return@repeat
                return ComponentAsset(
                    component,
                    version,
                    name,
                    url,
                    digest,
                    size,
                    release.optString("tag_name"),
                    release.optString("published_at"),
                )
            }
        }
        return null
    }

    fun encode(assets: List<ComponentAsset>): String = JSONArray().apply {
        assets.forEach { put(it.toJson()) }
    }.toString()

    fun decode(value: String): List<ComponentAsset> {
        val array = JSONArray(value)
        return List(array.length()) { index -> ComponentAsset.fromJson(array.getJSONObject(index)) }
            .filterNotNull()
    }
}

internal data class ComponentCatalogResult(
    val assets: List<ComponentAsset>,
    val checkedAt: Long,
    val cached: Boolean,
    val warning: String? = null,
)

internal data class ComponentUpdateSummary(
    val updates: List<ComponentAsset>,
    val checkedAt: Long,
    val cached: Boolean,
    val warning: String? = null,
)

/** Resolves only newer versions of components which are already installed. */
internal object ComponentUpdateResolver {
    fun resolve(
        assets: List<ComponentAsset>,
        apkStates: Map<ManagedComponent, InstalledApkState?>,
        coreStates: Map<ManagedComponent, InstalledCoreState>,
    ): List<ComponentAsset> = assets.filter { asset ->
        when (asset.component.kind) {
            ComponentKind.APK -> apkStates[asset.component]?.let { installed ->
                ComponentVersion.isNewer(asset.version, installed.versionName)
            } == true
            ComponentKind.CORE -> coreStates[asset.component]?.relationTo(asset.version) ==
                CoreReleaseRelation.UPDATE_AVAILABLE
        }
    }
}

/** Uses the same cached, verified release catalog as the downloads screen. */
internal class ComponentUpdateChecker(private val context: Context) {
    fun check(forceRefresh: Boolean = false): ComponentUpdateSummary {
        val catalog = ComponentReleaseClient(context).load(forceRefresh)
        val apkStates = ManagedComponent.entries
            .filter { it.kind == ComponentKind.APK }
            .associateWith { ApkComponentInstaller.installedState(context, it) }
        val coreStates = if (RetroArchCoreStore.selectedTree(context) == null) {
            emptyMap()
        } else {
            catalog.assets
                .filter { it.component.kind == ComponentKind.CORE }
                .associate { asset ->
                    asset.component to runCatching { RetroArchCoreStore.installedState(context, asset) }
                        .getOrDefault(InstalledCoreState())
                }
        }
        return ComponentUpdateSummary(
            updates = ComponentUpdateResolver.resolve(catalog.assets, apkStates, coreStates),
            checkedAt = catalog.checkedAt,
            cached = catalog.cached,
            warning = catalog.warning,
        )
    }
}

internal class ComponentReleaseClient(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(forceRefresh: Boolean = false): ComponentCatalogResult {
        val now = System.currentTimeMillis()
        val cached = cachedCatalog()
        if (!forceRefresh && cached != null && now - cached.checkedAt in 0 until CACHE_MILLIS) {
            return cached.copy(cached = true)
        }
        return runCatching {
            val companionJson = get(COMPANION_RELEASES_URL)
            val popTrackerJson = get(POPTRACKER_RELEASES_URL)
            val ladxhdJson = get(LADXHD_RELEASES_URL)
            val assets = ComponentReleaseParser.parse(companionJson, popTrackerJson, ladxhdJson)
            require(assets.map { it.component }.toSet() == ManagedComponent.entries.toSet()) {
                "The release catalog did not contain every required Android component."
            }
            preferences.edit()
                .putString(KEY_CATALOG, ComponentReleaseParser.encode(assets))
                .putLong(KEY_CHECKED_AT, now)
                .apply()
            ComponentCatalogResult(assets, now, cached = false)
        }.getOrElse { error ->
            cached?.copy(
                cached = true,
                warning = "Could not refresh releases; showing the last successful check. " +
                    (error.message ?: error.javaClass.simpleName),
            ) ?: throw error
        }
    }

    private fun cachedCatalog(): ComponentCatalogResult? {
        val value = preferences.getString(KEY_CATALOG, null) ?: return null
        val checkedAt = preferences.getLong(KEY_CHECKED_AT, 0L)
        return runCatching { ComponentReleaseParser.decode(value) }
            .getOrNull()
            ?.takeIf { assets ->
                assets.map { it.component }.toSet() == ManagedComponent.entries.toSet()
            }
            ?.let { ComponentCatalogResult(it, checkedAt, cached = true) }
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Archipelago-Companion-Android")
            .build()
        return HTTP_CLIENT.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub returned HTTP ${response.code}." }
            val body = checkNotNull(response.body) { "GitHub returned an empty response." }
            check(body.contentLength() <= MAX_METADATA_BYTES) { "GitHub release metadata is too large." }
            body.string().also {
                check(it.length.toLong() <= MAX_METADATA_BYTES) { "GitHub release metadata is too large." }
            }
        }
    }

    companion object {
        private const val PREFERENCES = "component_update_catalog"
        private const val KEY_CATALOG = "catalog"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val CACHE_MILLIS = 24L * 60L * 60L * 1_000L
        private const val MAX_METADATA_BYTES = 2L * 1024L * 1024L
        private const val COMPANION_RELEASES_URL =
            "https://api.github.com/repos/Odrannnn/Archipelago/releases?per_page=10"
        private const val POPTRACKER_RELEASES_URL =
            "https://api.github.com/repos/Odrannnn/PopTracker-Android/releases?per_page=10"
        private const val LADXHD_RELEASES_URL =
            "https://api.github.com/repos/Odrannnn/LADXHD-Archipelago/releases?per_page=10"
        private val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

internal object ComponentVersion {
    fun isNewer(available: String, installed: String): Boolean = compare(available, installed) > 0

    internal fun compare(left: String, right: String): Int {
        val leftTokens = tokens(left)
        val rightTokens = tokens(right)
        repeat(maxOf(leftTokens.size, rightTokens.size)) { index ->
            val leftToken = leftTokens.getOrNull(index) ?: "0"
            val rightToken = rightTokens.getOrNull(index) ?: "0"
            val comparison = compareToken(leftToken, rightToken)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun tokens(value: String): List<String> = Regex("[0-9]+|[a-zA-Z]+")
        .findAll(value)
        .map { it.value.lowercase() }
        .toList()

    private fun compareToken(left: String, right: String): Int {
        val leftNumber = left.toLongOrNull()
        val rightNumber = right.toLongOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> 1
            rightNumber != null -> -1
            else -> left.compareTo(right)
        }
    }
}
