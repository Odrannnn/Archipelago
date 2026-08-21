package eu.odran.archipelago

import android.content.Context
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal data class NativeDependencyWorld(
    val game: String,
    val minimumVersion: String? = null,
    val maximumVersion: String? = null,
) {
    fun matches(world: ImportedApWorld): Boolean = game == world.game &&
        (minimumVersion == null || ComponentVersion.compare(world.worldVersion, minimumVersion) >= 0) &&
        (maximumVersion == null || ComponentVersion.compare(world.worldVersion, maximumVersion) <= 0)

    fun toJson() = JSONObject()
        .put("game", game)
        .put("minimum_world_version", minimumVersion ?: "")
        .put("maximum_world_version", maximumVersion ?: "")

    companion object {
        fun fromJson(json: JSONObject): NativeDependencyWorld? {
            val game = json.optString("game").trim()
            if (game.isEmpty()) return null
            return NativeDependencyWorld(
                game,
                json.optString("minimum_world_version").trim().takeIf(String::isNotEmpty),
                json.optString("maximum_world_version").trim().takeIf(String::isNotEmpty),
            )
        }
    }
}

internal data class NativeDependencyAsset(
    val packageName: String,
    val version: String,
    val moduleName: String,
    val pythonAbi: String,
    val androidAbi: String,
    val minimumSdk: Int,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val byteCount: Long,
    val sourceUrl: String,
    val sourceSha256: String,
    val worlds: List<NativeDependencyWorld>,
) {
    fun compatibleWithDevice(): Boolean = minimumSdk <= Build.VERSION.SDK_INT &&
        androidAbi in Build.SUPPORTED_ABIS && pythonAbi in SUPPORTED_PYTHON_ABIS

    fun toJson() = JSONObject()
        .put("package", packageName)
        .put("version", version)
        .put("module", moduleName)
        .put("python_abi", pythonAbi)
        .put("android_abi", androidAbi)
        .put("minimum_sdk", minimumSdk)
        .put("file_name", fileName)
        .put("download_url", downloadUrl)
        .put("sha256", sha256)
        .put("byte_count", byteCount)
        .put("source_url", sourceUrl)
        .put("source_sha256", sourceSha256)
        .put("worlds", JSONArray().apply { worlds.forEach { put(it.toJson()) } })

    companion object {
        val SUPPORTED_PYTHON_ABIS = setOf("cp312", "abi3")
    }
}

internal object NativeDependencyCatalogParser {
    private val packagePattern = Regex("[a-z0-9][a-z0-9._-]*")
    private val versionPattern = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
    private val modulePattern = Regex("[A-Za-z_][A-Za-z0-9_.]*")
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun parse(releaseJson: String, indexJson: String): List<NativeDependencyAsset> {
        val release = JSONObject(releaseJson)
        require(release.optString("tag_name") == NativeDependencyCatalogClient.RELEASE_TAG) {
            "Unexpected native dependency release tag."
        }
        val releaseAssets = release.getJSONArray("assets").asObjects().associateBy { it.optString("name") }
        val index = JSONObject(indexJson)
        require(index.optInt("schema") == 1) { "Unsupported native dependency catalog schema." }
        val parsed = index.getJSONArray("packages").asObjects().map { entry ->
            val packageName = entry.getString("package").lowercase(Locale.ROOT)
            val version = entry.getString("version").trim()
            val module = entry.getString("module").trim()
            val pythonAbi = entry.getString("python_abi").trim()
            val androidAbi = entry.getString("android_abi").trim()
            val minimumSdk = entry.getInt("minimum_sdk")
            val fileName = entry.getString("file_name").trim()
            val expectedSha = entry.getString("sha256").lowercase(Locale.ROOT)
            val expectedBytes = entry.getLong("byte_count")
            require(packagePattern.matches(packageName) && versionPattern.matches(version)) {
                "Invalid package identity."
            }
            require(modulePattern.matches(module)) { "Invalid dependency module name." }
            require(pythonAbi in NativeDependencyAsset.SUPPORTED_PYTHON_ABIS) { "Unsupported Python ABI." }
            require(androidAbi in setOf("arm64-v8a", "armeabi-v7a", "x86_64")) { "Unsupported Android ABI." }
            require(minimumSdk >= 26 && fileName.matches(Regex("[A-Za-z0-9._+-]+\\.zip"))) {
                "Invalid Android package metadata."
            }
            require(digestPattern.matches(expectedSha) && expectedBytes in 1..MAX_PACKAGE_BYTES) {
                "Invalid dependency integrity metadata."
            }
            val releaseAsset = releaseAssets[fileName] ?: error("Release is missing $fileName.")
            val releaseSha = releaseAsset.optString("digest").removePrefix("sha256:").lowercase(Locale.ROOT)
            val releaseBytes = releaseAsset.optLong("size", -1)
            val downloadUrl = releaseAsset.optString("browser_download_url")
            require(releaseSha == expectedSha && releaseBytes == expectedBytes) {
                "$fileName does not match the curated catalog."
            }
            require(downloadUrl.startsWith("https://github.com/")) { "Untrusted dependency download URL." }
            val worlds = entry.getJSONArray("worlds").asObjects().mapNotNull(NativeDependencyWorld::fromJson)
            require(worlds.isNotEmpty()) { "$packageName is not assigned to an APWorld." }
            val sourceUrl = entry.getString("source_url")
            require(sourceUrl.startsWith("https://files.pythonhosted.org/")) {
                "Untrusted upstream source URL."
            }
            NativeDependencyAsset(
                packageName,
                version,
                module,
                pythonAbi,
                androidAbi,
                minimumSdk,
                fileName,
                downloadUrl,
                expectedSha,
                expectedBytes,
                sourceUrl,
                entry.getString("source_sha256").lowercase(Locale.ROOT).also {
                    require(digestPattern.matches(it)) { "Invalid upstream source digest." }
                },
                worlds,
            )
        }
        require(parsed.distinctBy { Triple(it.packageName, it.version, it.androidAbi) }.size == parsed.size) {
            "The dependency catalog contains a duplicate package build."
        }
        return parsed
    }

    fun encode(assets: List<NativeDependencyAsset>): String = JSONArray().apply {
        assets.forEach { put(it.toJson()) }
    }.toString()

    fun decode(value: String): List<NativeDependencyAsset> = JSONArray(value).asObjects().map { entry ->
        NativeDependencyAsset(
            entry.getString("package"),
            entry.getString("version"),
            entry.getString("module"),
            entry.getString("python_abi"),
            entry.getString("android_abi"),
            entry.getInt("minimum_sdk"),
            entry.getString("file_name"),
            entry.getString("download_url"),
            entry.getString("sha256"),
            entry.getLong("byte_count"),
            entry.getString("source_url"),
            entry.getString("source_sha256"),
            entry.getJSONArray("worlds").asObjects().mapNotNull(NativeDependencyWorld::fromJson),
        )
    }

    private fun JSONArray.asObjects(): List<JSONObject> = List(length()) { getJSONObject(it) }
    private const val MAX_PACKAGE_BYTES = 64L * 1024L * 1024L
}

internal data class NativeDependencyCatalogResult(
    val assets: List<NativeDependencyAsset>,
    val checkedAt: Long,
    val cached: Boolean,
    val warning: String? = null,
)

internal class NativeDependencyCatalogClient(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(forceRefresh: Boolean = false): NativeDependencyCatalogResult {
        val now = System.currentTimeMillis()
        val cached = cachedCatalog()
        if (!forceRefresh && cached != null && now - cached.checkedAt in 0 until CACHE_MILLIS) {
            return cached.copy(cached = true)
        }
        return runCatching {
            val releaseJson = getBytes(RELEASE_URL, MAX_RELEASE_BYTES).toString(Charsets.UTF_8)
            val release = JSONObject(releaseJson)
            val indexAsset = release.getJSONArray("assets").let { assets ->
                (0 until assets.length()).asSequence()
                    .map(assets::getJSONObject)
                    .firstOrNull { it.optString("name") == INDEX_FILE }
            } ?: error("The Android dependency release has no catalog.")
            val indexUrl = indexAsset.getString("browser_download_url")
            require(indexUrl.startsWith("https://github.com/")) { "Untrusted catalog download URL." }
            val expectedDigest = indexAsset.getString("digest").removePrefix("sha256:").lowercase(Locale.ROOT)
            val expectedBytes = indexAsset.getLong("size")
            require(expectedDigest.matches(Regex("[0-9a-f]{64}")) && expectedBytes in 1..MAX_INDEX_BYTES) {
                "The Android dependency catalog has invalid integrity metadata."
            }
            val indexBytes = getBytes(indexUrl, MAX_INDEX_BYTES)
            require(indexBytes.size.toLong() == expectedBytes && indexBytes.sha256Hex() == expectedDigest) {
                "The Android dependency catalog failed GitHub SHA-256 verification."
            }
            val assets = NativeDependencyCatalogParser.parse(releaseJson, indexBytes.toString(Charsets.UTF_8))
                .filter(NativeDependencyAsset::compatibleWithDevice)
            preferences.edit()
                .putString(KEY_CATALOG, NativeDependencyCatalogParser.encode(assets))
                .putLong(KEY_CHECKED_AT, now)
                .apply()
            NativeDependencyCatalogResult(assets, now, cached = false)
        }.getOrElse { error ->
            cached?.copy(
                cached = true,
                warning = "Could not refresh Android dependencies; showing the last verified catalog. " +
                    (error.message ?: error.javaClass.simpleName),
            ) ?: throw error
        }
    }

    private fun cachedCatalog(): NativeDependencyCatalogResult? {
        val value = preferences.getString(KEY_CATALOG, null) ?: return null
        val checkedAt = preferences.getLong(KEY_CHECKED_AT, 0L)
        return runCatching { NativeDependencyCatalogParser.decode(value) }.getOrNull()
            ?.let { NativeDependencyCatalogResult(it, checkedAt, cached = true) }
    }

    private fun getBytes(url: String, maximum: Long): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json, application/octet-stream")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Archipelago-Companion-Android")
            .build()
        return HTTP_CLIENT.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub returned HTTP ${response.code}." }
            check(response.request.url.isHttps) { "GitHub redirected the catalog away from HTTPS." }
            val body = checkNotNull(response.body) { "GitHub returned an empty response." }
            val declared = body.contentLength()
            check(declared < 0 || declared <= maximum) { "GitHub metadata is too large." }
            body.bytes().also { check(it.size.toLong() <= maximum) { "GitHub metadata is too large." } }
        }
    }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val RELEASE_TAG = "android-python-dependencies"
        private const val INDEX_FILE = "android-python-dependencies-v1.json"
        private const val RELEASE_URL =
            "https://api.github.com/repos/Odrannnn/Archipelago/releases/tags/$RELEASE_TAG"
        private const val PREFERENCES = "native_dependency_catalog"
        private const val KEY_CATALOG = "catalog"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val CACHE_MILLIS = 24L * 60L * 60L * 1_000L
        private const val MAX_RELEASE_BYTES = 2L * 1024L * 1024L
        private const val MAX_INDEX_BYTES = 1L * 1024L * 1024L
        private val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

internal data class InstalledNativeDependency(
    val packageName: String,
    val version: String,
    val moduleName: String,
    val sha256: String,
    val sitePackages: String,
    val installedAt: Long,
)

internal data class NativeDependencyProvisionResult(
    val catalog: NativeDependencyCatalogResult,
    val required: List<NativeDependencyAsset>,
    val installed: List<NativeDependencyAsset>,
)

/** Installs only dependencies assigned to reviewed APWorld/version ranges in the verified catalog. */
internal object NativeDependencyProvisioner {
    @Synchronized
    fun installFor(
        context: Context,
        worlds: List<ImportedApWorld>,
        onStarting: (NativeDependencyAsset) -> Unit = {},
        onProgress: (NativeDependencyAsset, Long, Long) -> Unit = { _, _, _ -> },
    ): NativeDependencyProvisionResult {
        val catalog = runCatching { NativeDependencyCatalogClient(context).load(forceRefresh = false) }
            .getOrElse { error ->
                return NativeDependencyProvisionResult(
                    NativeDependencyCatalogResult(
                        assets = emptyList(),
                        checkedAt = System.currentTimeMillis(),
                        cached = false,
                        warning = "Automatic Android dependency check unavailable: " +
                            (error.message ?: error.javaClass.simpleName),
                    ),
                    required = emptyList(),
                    installed = emptyList(),
                )
            }
        return installFromCatalog(context, catalog, worlds, onStarting, onProgress)
    }

    @Synchronized
    fun installFromCatalog(
        context: Context,
        catalog: NativeDependencyCatalogResult,
        worlds: List<ImportedApWorld>,
        onStarting: (NativeDependencyAsset) -> Unit = {},
        onProgress: (NativeDependencyAsset, Long, Long) -> Unit = { _, _, _ -> },
    ): NativeDependencyProvisionResult {
        val required = requiredAssets(catalog.assets, worlds)
        val installed = buildList {
            required.forEach { asset ->
                if (NativeDependencyStore.isInstalled(context, asset)) return@forEach
                onStarting(asset)
                NativeDependencyStore.downloadAndInstall(context, asset) { downloaded, total ->
                    onProgress(asset, downloaded, total)
                }
                add(asset)
            }
        }
        return NativeDependencyProvisionResult(catalog, required, installed)
    }

    internal fun requiredAssets(
        assets: List<NativeDependencyAsset>,
        worlds: List<ImportedApWorld>,
    ): List<NativeDependencyAsset> = assets
        .filter { asset -> asset.worlds.any { rule -> worlds.any(rule::matches) } }
        .groupBy(NativeDependencyAsset::packageName)
        .values
        .map { candidates ->
            candidates.maxWithOrNull { left, right -> ComponentVersion.compare(left.version, right.version) }
                ?: error("Empty native dependency candidate group.")
        }
        .sortedBy(NativeDependencyAsset::packageName)
}

internal object NativeDependencyStore {
    private val packagePattern = Regex("[a-z0-9][a-z0-9._-]*")

    fun list(context: Context): List<InstalledNativeDependency> = runCatching {
        val file = registryFile(context)
        if (!file.isFile) return@runCatching emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            InstalledNativeDependency(
                entry.getString("package"),
                entry.getString("version"),
                entry.getString("module"),
                entry.getString("sha256"),
                entry.getString("site_packages"),
                entry.getLong("installed_at"),
            )
        }.filter { File(root(context), it.sitePackages).isDirectory }
    }.getOrDefault(emptyList())

    fun isInstalled(context: Context, asset: NativeDependencyAsset): Boolean = list(context).any {
        it.packageName == asset.packageName && it.version == asset.version && it.sha256 == asset.sha256
    }

    fun downloadAndInstall(
        context: Context,
        asset: NativeDependencyAsset,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): InstalledNativeDependency {
        require(asset.compatibleWithDevice()) { "${asset.packageName} is not compatible with this device." }
        val downloaded = download(context, asset, onProgress)
        return install(context, asset, downloaded)
    }

    fun remove(context: Context, packageName: String): Boolean {
        if (!packagePattern.matches(packageName)) return false
        val packageRoot = File(root(context), packageName).canonicalFile
        if (packageRoot.parentFile != root(context).canonicalFile) return false
        val removed = !packageRoot.exists() || packageRoot.deleteRecursively()
        if (removed) save(context, list(context).filterNot { it.packageName == packageName })
        return removed
    }

    private fun download(
        context: Context,
        asset: NativeDependencyAsset,
        onProgress: (Long, Long) -> Unit,
    ): File {
        val cache = File(context.cacheDir, "native_dependencies").apply { check(isDirectory || mkdirs()) }
        val destination = File(cache, asset.fileName)
        if (destination.isFile && destination.length() == asset.byteCount && destination.sha256Hex() == asset.sha256) {
            return destination
        }
        val temporary = File(cache, ".${asset.fileName}.part")
        check(!temporary.exists() || temporary.delete()) { "Could not clear the interrupted dependency download." }
        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Archipelago-Companion-Android")
            .build()
        try {
            DOWNLOAD_CLIENT.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Dependency download returned HTTP ${response.code}." }
                check(response.request.url.isHttps) { "Dependency download was redirected away from HTTPS." }
                val body = checkNotNull(response.body) { "Dependency download was empty." }
                var written = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().buffered().use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            check(written <= asset.byteCount) { "Dependency exceeded its declared size." }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            onProgress(written, asset.byteCount)
                        }
                    }
                }
                check(written == asset.byteCount) { "Dependency download ended early." }
                check(digest.digest().toHex() == asset.sha256) { "Dependency failed SHA-256 verification." }
            }
            if (destination.exists()) check(destination.delete())
            check(temporary.renameTo(destination)) { "Could not finish the dependency download." }
            return destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun install(
        context: Context,
        asset: NativeDependencyAsset,
        archiveFile: File,
    ): InstalledNativeDependency {
        val dependencyRoot = root(context)
        val staging = File(dependencyRoot, ".stage-${System.nanoTime()}")
        val destination = File(File(dependencyRoot, asset.packageName), asset.version)
        try {
            ZipFile(archiveFile).use { archive ->
                val entries = archive.entries().toList()
                require(entries.size in 2..MAX_ENTRIES) { "Dependency package has an invalid file count." }
                require(entries.map { it.name }.distinct().size == entries.size) {
                    "Dependency package contains duplicate paths."
                }
                val manifestEntry = entries.singleOrNull { it.name == "dependency.json" }
                    ?: error("Dependency package has no unique dependency.json manifest.")
                val manifest = archive.getInputStream(manifestEntry).bufferedReader().use { JSONObject(it.readText()) }
                require(manifest.optInt("schema") == 1 &&
                    manifest.optString("package") == asset.packageName &&
                    manifest.optString("version") == asset.version &&
                    manifest.optString("module") == asset.moduleName &&
                    manifest.optString("python_abi") == asset.pythonAbi &&
                    manifest.optString("android_abi") == asset.androidAbi &&
                    manifest.optInt("minimum_sdk") == asset.minimumSdk &&
                    manifest.optString("source_url") == asset.sourceUrl &&
                    manifest.optString("source_sha256") == asset.sourceSha256
                ) { "Dependency manifest does not match the curated catalog." }

                val stagedSitePackages = File(staging, "site-packages").apply { mkdirs() }
                val stagedRoot = stagedSitePackages.canonicalFile
                var totalBytes = 0L
                entries.filterNot { it.isDirectory }.forEach { entry ->
                    if (entry.name == "dependency.json") return@forEach
                    require(entry.name.startsWith("site-packages/") && isSafeZipPath(entry.name)) {
                        "Unsafe dependency path: ${entry.name}"
                    }
                    val relative = entry.name.removePrefix("site-packages/")
                    require(relative.isNotBlank() && entry.size <= MAX_ENTRY_BYTES) {
                        "Invalid dependency entry: ${entry.name}"
                    }
                    val target = File(stagedSitePackages, relative).canonicalFile
                    require(target.path.startsWith(stagedRoot.path + File.separator)) {
                        "Unsafe dependency path: ${entry.name}"
                    }
                    target.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var entryBytes = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                totalBytes += count
                                require(entryBytes <= MAX_ENTRY_BYTES && totalBytes <= MAX_EXPANDED_BYTES) {
                                    "Dependency package expands beyond the permitted size."
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                val topLevelModule = asset.moduleName.substringBefore('.')
                require(File(stagedSitePackages, topLevelModule).exists() ||
                    stagedSitePackages.listFiles().orEmpty().any {
                        it.isFile && (it.name == "$topLevelModule.so" || it.name.startsWith("$topLevelModule."))
                    }
                ) {
                    "Dependency package does not contain ${asset.moduleName}."
                }
            }
            destination.parentFile?.mkdirs()
            if (destination.exists()) require(destination.deleteRecursively()) { "Could not replace the dependency." }
            if (!staging.renameTo(destination)) {
                staging.copyRecursively(destination, overwrite = false)
                staging.deleteRecursively()
            }
            val relativeSitePackages = "${asset.packageName}/${asset.version}/site-packages"
            val record = InstalledNativeDependency(
                asset.packageName,
                asset.version,
                asset.moduleName,
                asset.sha256,
                relativeSitePackages,
                System.currentTimeMillis(),
            )
            save(context, list(context).filterNot { it.packageName == asset.packageName } + record)
            return record
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun save(context: Context, records: List<InstalledNativeDependency>) {
        val data = JSONArray().apply {
            records.sortedBy { it.packageName }.forEach { record ->
                put(JSONObject()
                    .put("package", record.packageName)
                    .put("version", record.version)
                    .put("module", record.moduleName)
                    .put("sha256", record.sha256)
                    .put("site_packages", record.sitePackages)
                    .put("installed_at", record.installedAt))
            }
        }
        val target = registryFile(context)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(data.toString(2), Charsets.UTF_8)
        if (target.exists()) require(target.delete()) { "Could not update the dependency registry." }
        require(temporary.renameTo(target)) { "Could not save the dependency registry." }
    }

    private fun root(context: Context) = File(ImportedApWorldStore.runtimeRoot(context), "python_dependencies").apply {
        check(isDirectory || mkdirs()) { "Could not create the dependency directory." }
    }

    private fun registryFile(context: Context) = File(root(context), "installed.json")

    private fun isSafeZipPath(name: String): Boolean = name.isNotBlank() && !name.contains('\\') &&
        !name.startsWith('/') && !name.contains('\u0000') &&
        name.split('/').none { it == "." || it == ".." } && !Regex("^[A-Za-z]:").containsMatchIn(name)

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val MAX_ENTRIES = 2_000
    private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 128L * 1024L * 1024L
    private val DOWNLOAD_CLIENT = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
}
