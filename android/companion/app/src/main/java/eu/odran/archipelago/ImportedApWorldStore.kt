package eu.odran.archipelago

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

data class ImportedApWorld(
    val packageName: String,
    val game: String,
    val worldVersion: String,
    val minimumApVersion: String?,
    val maximumApVersion: String?,
    val sourceName: String,
    val sha256: String,
    val installedAt: Long,
)

/** Owns trusted APWorld packages extracted into Archipelago's app-private user-world folder. */
object ImportedApWorldStore {
    private const val CORE_VERSION = "0.6.8"
    private const val CONTAINER_VERSION = 7
    private const val MAX_ENTRIES = 5_000
    private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 128L * 1024L * 1024L
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val builtInGames = setOf("Metroid Fusion", "The Minish Cap")

    fun runtimeRoot(context: Context): File = File(context.filesDir, "offline_generator").apply { mkdirs() }

    private fun worldsRoot(context: Context): File = File(runtimeRoot(context), "worlds").apply { mkdirs() }

    private fun registryFile(context: Context) = File(runtimeRoot(context), "installed_apworlds.json")

    fun list(context: Context): List<ImportedApWorld> = runCatching {
        val file = registryFile(context)
        if (!file.isFile) return@runCatching emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ImportedApWorld(
                packageName = item.getString("package"),
                game = item.getString("game"),
                worldVersion = item.optString("world_version", "0.0.0"),
                minimumApVersion = item.optString("minimum_ap_version").takeIf { it.isNotBlank() },
                maximumApVersion = item.optString("maximum_ap_version").takeIf { it.isNotBlank() },
                sourceName = item.optString("source_name", "Imported APWorld"),
                sha256 = item.getString("sha256"),
                installedAt = item.optLong("installed_at"),
            )
        }.filter { File(worldsRoot(context), it.packageName).isDirectory }
    }.getOrDefault(emptyList())

    fun install(context: Context, uri: Uri): ImportedApWorld {
        val sourceName = displayName(context, uri)
        val incoming = File.createTempFile("incoming-", ".apworld", context.cacheDir)
        val staging = File(context.cacheDir, "apworld-stage-${System.nanoTime()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(incoming).use { output -> input.copyTo(output) }
            } ?: error("Could not open the selected APWorld")
            if (incoming.length() <= 0L) error("The selected APWorld is empty")

            ZipFile(incoming).use { archive ->
                val entries = archive.entries().toList()
                if (entries.size > MAX_ENTRIES) error("APWorld contains too many files (${entries.size})")
                entries.forEach(::validateEntryName)
                val manifests = entries.filter { !it.isDirectory && it.name.replace('\\', '/').endsWith("archipelago.json") }
                if (manifests.size != 1) error("APWorld must contain exactly one archipelago.json manifest")
                val manifestEntry = manifests.single()
                val manifest = archive.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use {
                    JSONObject(it.readText())
                }
                val game = manifest.optString("game").trim().takeIf { it.isNotEmpty() }
                    ?: error("archipelago.json does not declare a game")
                val compatible = manifest.optInt("compatible_version", CONTAINER_VERSION)
                if (compatible > CONTAINER_VERSION) {
                    error("$game needs APWorld container $compatible; this app supports $CONTAINER_VERSION")
                }
                val minimum = manifest.optString("minimum_ap_version").takeIf { it.isNotBlank() }
                val maximum = manifest.optString("maximum_ap_version").takeIf { it.isNotBlank() }
                if (minimum != null && compareVersions(minimum, CORE_VERSION) > 0) {
                    error("$game requires Archipelago $minimum or newer; this app embeds $CORE_VERSION")
                }
                if (maximum != null && compareVersions(maximum, CORE_VERSION) < 0) {
                    error("$game supports Archipelago only through $maximum; this app embeds $CORE_VERSION")
                }
                if (game in builtInGames) error("$game is bundled with the app and cannot be replaced by an import")
                if (list(context).none { it.game == game } &&
                    OfflineGenerator.cachedCatalog().any { it.game == game && it.source == "imported" }
                ) {
                    error("$game is still loaded in this app process. Fully restart the companion before importing another version")
                }

                val normalizedManifest = manifestEntry.name.replace('\\', '/').trimStart('/')
                val manifestParent = normalizedManifest.substringBeforeLast('/', "")
                val parts = manifestParent.split('/').filter { it.isNotBlank() }
                val packageName = when {
                    parts.size == 1 -> parts[0]
                    parts.size == 2 && parts[0] == "worlds" -> parts[1]
                    else -> error("archipelago.json must be at <package>/archipelago.json")
                }
                if (!packagePattern.matches(packageName)) error("Invalid Python package name: $packageName")
                val sourcePrefix = if (parts.firstOrNull() == "worlds") "worlds/$packageName/" else "$packageName/"
                val initName = "${sourcePrefix}__init__.py"
                if (entries.none { it.name.replace('\\', '/') == initName }) {
                    error("$packageName has no __init__.py")
                }

                val installed = list(context)
                if (installed.any { it.game == game }) error("$game is already installed; remove it before importing another version")
                if (installed.any { it.packageName == packageName }) error("Package $packageName is already installed")
                val destination = File(worldsRoot(context), packageName)
                if (destination.exists()) error("Package folder $packageName already exists")

                val stagedPackage = File(staging, packageName).apply { mkdirs() }
                val stagedRoot = stagedPackage.canonicalFile
                var totalBytes = 0L
                val extractedNames = mutableSetOf<String>()
                for (entry in entries) {
                    val name = entry.name.replace('\\', '/')
                    if (!name.startsWith(sourcePrefix)) continue
                    val relative = name.removePrefix(sourcePrefix)
                    if (relative.isBlank()) continue
                    if (!extractedNames.add(relative)) error("APWorld contains a duplicate path: $relative")
                    if (entry.size > MAX_ENTRY_BYTES) error("APWorld file is too large: $relative")
                    val target = File(stagedPackage, relative).canonicalFile
                    if (target != stagedRoot && !target.path.startsWith(stagedRoot.path + File.separator)) {
                        error("Unsafe APWorld path: $relative")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
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
                                    if (entryBytes > MAX_ENTRY_BYTES) error("APWorld file is too large: $relative")
                                    if (totalBytes > MAX_TOTAL_BYTES) {
                                        error("APWorld expands beyond ${MAX_TOTAL_BYTES / 1024 / 1024} MiB")
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                }
                if (!File(stagedPackage, "archipelago.json").isFile || !File(stagedPackage, "__init__.py").isFile) {
                    error("APWorld package was incomplete after extraction")
                }
                if (!stagedPackage.renameTo(destination)) {
                    stagedPackage.copyRecursively(destination, overwrite = false)
                    stagedPackage.deleteRecursively()
                }

                val record = ImportedApWorld(
                    packageName = packageName,
                    game = game,
                    worldVersion = manifest.optString("world_version", "0.0.0"),
                    minimumApVersion = minimum,
                    maximumApVersion = maximum,
                    sourceName = sourceName,
                    sha256 = sha256(incoming),
                    installedAt = System.currentTimeMillis(),
                )
                try {
                    save(context, installed + record)
                } catch (error: Throwable) {
                    destination.deleteRecursively()
                    throw error
                }
                return record
            }
        } finally {
            incoming.delete()
            staging.deleteRecursively()
        }
    }

    fun remove(context: Context, packageName: String): Boolean {
        if (!packagePattern.matches(packageName)) return false
        val root = worldsRoot(context).canonicalFile
        val target = File(root, packageName).canonicalFile
        if (target.parentFile != root) return false
        val removed = !target.exists() || target.deleteRecursively()
        if (removed) save(context, list(context).filterNot { it.packageName == packageName })
        return removed
    }

    private fun save(context: Context, worlds: List<ImportedApWorld>) {
        val array = JSONArray()
        worlds.sortedBy { it.game.lowercase(Locale.ROOT) }.forEach { world ->
            array.put(JSONObject().apply {
                put("package", world.packageName)
                put("game", world.game)
                put("world_version", world.worldVersion)
                put("minimum_ap_version", world.minimumApVersion ?: "")
                put("maximum_ap_version", world.maximumApVersion ?: "")
                put("source_name", world.sourceName)
                put("sha256", world.sha256)
                put("installed_at", world.installedAt)
            })
        }
        val target = registryFile(context)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(array.toString(2), Charsets.UTF_8)
        if (target.exists() && !target.delete()) error("Could not update the APWorld registry")
        if (!temporary.renameTo(target)) error("Could not save the APWorld registry")
    }

    private fun validateEntryName(entry: java.util.zip.ZipEntry) {
        val name = entry.name
        if (name.isBlank() || name.contains('\\') || name.startsWith('/') || name.contains('\u0000')) {
            error("Unsafe APWorld path: $name")
        }
        val segments = name.split('/')
        if (segments.any { it == ".." || it == "." } || Regex("^[A-Za-z]:").containsMatchIn(name)) {
            error("Unsafe APWorld path: $name")
        }
        if (entry.compressedSize > MAX_ENTRY_BYTES || entry.size > MAX_ENTRY_BYTES) {
            error("APWorld file is too large: $name")
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        fun parse(value: String) = value.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parse(left)
        val b = parse(right)
        for (index in 0 until maxOf(a.size, b.size, 3)) {
            val comparison = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "Imported APWorld"
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Imported APWorld"
    }
}
