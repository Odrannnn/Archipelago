package eu.odran.archipelago

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class BackupArchiveSource(
    val archivePath: String,
    val file: File,
)

internal data class BackupArchiveEntry(
    val path: String,
    val byteCount: Long,
    val sha256: String,
    val modifiedAt: Long,
)

internal data class BackupArchiveManifest(
    val appId: String,
    val appVersion: String,
    val createdAt: Long,
    val roots: List<String>,
    val entries: List<BackupArchiveEntry>,
)

internal data class ExtractedBackupArchive(
    val manifest: BackupArchiveManifest,
    val preferences: ByteArray,
    val stagingRoot: File,
)

/** Streaming, versioned backup container with strict extraction boundaries. */
internal object BackupArchive {
    const val FORMAT = "archipelago-companion-backup"
    const val FORMAT_VERSION = 1
    const val PREFERENCES_PATH = "preferences.json"
    const val MANIFEST_PATH = "manifest.json"
    val SUPPORTED_ROOTS = listOf("offline_generator", "saved_yamls", "patched_roms", "base_rom")

    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_ENTRIES = 50_000
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L
    private const val MAX_METADATA_BYTES = 8 * 1024 * 1024
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

    fun write(
        output: OutputStream,
        appId: String,
        appVersion: String,
        createdAt: Long,
        sources: List<BackupArchiveSource>,
        preferences: ByteArray,
    ): BackupArchiveManifest {
        require(preferences.size <= MAX_METADATA_BYTES) { "Backup settings are unexpectedly large." }
        require(sources.size + 2 <= MAX_ENTRIES) { "The backup contains too many files." }
        require(sources.all { it.file.length() <= MAX_ENTRY_BYTES }) { "A backup file is too large." }
        require(sources.sumOf { it.file.length() } + preferences.size <= MAX_TOTAL_BYTES) {
            "App data is too large for one backup archive."
        }
        val paths = mutableSetOf<String>()
        sources.forEach { source ->
            require(source.file.isFile) { "Backup source is missing: ${source.archivePath}" }
            validateDataPath(source.archivePath)
            require(paths.add(source.archivePath)) { "Duplicate backup path: ${source.archivePath}" }
        }
        require(paths.add(PREFERENCES_PATH)) { "Duplicate backup settings entry." }

        val records = mutableListOf<BackupArchiveEntry>()
        var writtenBytes = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            sources.sortedBy { it.archivePath }.forEach { source ->
                val record = writeEntry(zip, source.archivePath, source.file.lastModified()) { sink ->
                    source.file.inputStream().buffered().use { input -> input.copyAndDigestTo(sink) }
                }
                require(record.byteCount <= MAX_ENTRY_BYTES) { "A backup file is too large: ${source.archivePath}" }
                writtenBytes += record.byteCount
                require(writtenBytes <= MAX_TOTAL_BYTES) { "App data is too large for one backup archive." }
                records += record
            }
            val settingsRecord = writeEntry(zip, PREFERENCES_PATH, createdAt) { sink ->
                sink.write(preferences)
                MessageDigest.getInstance("SHA-256").digest(preferences) to preferences.size.toLong()
            }
            writtenBytes += settingsRecord.byteCount
            require(writtenBytes <= MAX_TOTAL_BYTES) { "App data is too large for one backup archive." }
            records += settingsRecord

            val manifest = BackupArchiveManifest(
                appId = appId,
                appVersion = appVersion,
                createdAt = createdAt,
                roots = SUPPORTED_ROOTS,
                entries = records,
            )
            val manifestBytes = encodeManifest(manifest).toByteArray(Charsets.UTF_8)
            require(manifestBytes.size <= MAX_METADATA_BYTES) { "Backup manifest is unexpectedly large." }
            zip.putNextEntry(ZipEntry(MANIFEST_PATH).apply { time = createdAt })
            zip.write(manifestBytes)
            zip.closeEntry()
            return manifest
        }
    }

    fun extract(
        input: InputStream,
        stagingRoot: File,
        expectedAppId: String,
    ): ExtractedBackupArchive {
        require(stagingRoot.mkdirs() || stagingRoot.isDirectory) { "Could not create restore staging storage." }
        require(stagingRoot.listFiles().isNullOrEmpty()) { "Restore staging storage is not empty." }
        val canonicalRoot = stagingRoot.canonicalFile
        val seenPaths = mutableSetOf<String>()
        val extracted = linkedMapOf<String, BackupArchiveEntry>()
        var totalBytes = 0L
        var entryCount = 0
        var manifestBytes: ByteArray? = null
        var preferences: ByteArray? = null

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= MAX_ENTRIES) { "Backup contains too many entries." }
                val path = entry.name
                validateEntryPath(path)
                require(!entry.isDirectory) { "Backup contains an unexpected directory entry: $path" }
                require(seenPaths.add(path)) { "Backup contains a duplicate path: $path" }
                if (entry.size > MAX_ENTRY_BYTES || entry.compressedSize > MAX_ENTRY_BYTES) {
                    error("Backup entry is too large: $path")
                }

                when (path) {
                    MANIFEST_PATH -> {
                        manifestBytes = zip.readLimited(MAX_METADATA_BYTES, "Backup manifest is too large.")
                    }
                    PREFERENCES_PATH -> {
                        val bytes = zip.readLimited(MAX_METADATA_BYTES, "Backup settings are too large.")
                        preferences = bytes
                        totalBytes += bytes.size
                        require(totalBytes <= MAX_TOTAL_BYTES) { "Backup expands beyond the supported size." }
                        extracted[path] = BackupArchiveEntry(
                            path,
                            bytes.size.toLong(),
                            bytes.sha256Hex(),
                            entry.time.coerceAtLeast(0L),
                        )
                    }
                    else -> {
                        validateDataPath(path)
                        val destination = File(canonicalRoot, path).canonicalFile
                        require(destination.path.startsWith(canonicalRoot.path + File.separator)) {
                            "Unsafe backup path: $path"
                        }
                        require(destination.parentFile?.mkdirs() != false || destination.parentFile?.isDirectory == true) {
                            "Could not create restore staging storage."
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var entryBytes = 0L
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                totalBytes += count
                                require(entryBytes <= MAX_ENTRY_BYTES) { "Backup entry is too large: $path" }
                                require(totalBytes <= MAX_TOTAL_BYTES) { "Backup expands beyond the supported size." }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                        if (entry.time > 0L) destination.setLastModified(entry.time)
                        extracted[path] = BackupArchiveEntry(
                            path,
                            entryBytes,
                            digest.digest().toHex(),
                            entry.time.coerceAtLeast(0L),
                        )
                    }
                }
                zip.closeEntry()
            }
        }

        val manifest = decodeManifest(
            manifestBytes?.toString(Charsets.UTF_8) ?: error("Backup manifest is missing."),
        )
        require(manifest.appId == expectedAppId) { "This backup belongs to a different app." }
        require(manifest.roots == SUPPORTED_ROOTS) { "Backup storage layout is not supported." }
        val declared = manifest.entries.associateBy { it.path }
        require(declared.size == manifest.entries.size) { "Backup manifest contains duplicate paths." }
        require(declared.keys == extracted.keys) { "Backup contents do not match its manifest." }
        declared.forEach { (path, expected) ->
            val actual = extracted.getValue(path)
            require(expected.byteCount == actual.byteCount && expected.sha256 == actual.sha256) {
                "Backup integrity check failed for $path."
            }
        }
        return ExtractedBackupArchive(
            manifest,
            preferences ?: error("Backup settings are missing."),
            stagingRoot,
        )
    }

    internal fun validateEntryPath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000')) {
            "Unsafe backup path: $path"
        }
        require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "Unsafe backup path: $path" }
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe backup path: $path" }
    }

    private fun validateDataPath(path: String) {
        validateEntryPath(path)
        val parts = path.split('/')
        require(parts.size >= 3 && parts[0] == "data" && parts[1] in SUPPORTED_ROOTS) {
            "Unsupported backup path: $path"
        }
    }

    private fun writeEntry(
        zip: ZipOutputStream,
        path: String,
        modifiedAt: Long,
        write: (OutputStream) -> Pair<ByteArray, Long>,
    ): BackupArchiveEntry {
        zip.putNextEntry(ZipEntry(path).apply { time = modifiedAt.coerceAtLeast(0L) })
        val (digest, bytes) = write(zip)
        zip.closeEntry()
        return BackupArchiveEntry(path, bytes, digest.toHex(), modifiedAt.coerceAtLeast(0L))
    }

    private fun InputStream.copyAndDigestTo(output: OutputStream): Pair<ByteArray, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        return digest.digest() to total
    }

    private fun InputStream.readLimited(maxBytes: Int, errorMessage: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { errorMessage }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun encodeManifest(manifest: BackupArchiveManifest) = JSONObject().apply {
        put("format", FORMAT)
        put("format_version", FORMAT_VERSION)
        put("app_id", manifest.appId)
        put("app_version", manifest.appVersion)
        put("created_at", manifest.createdAt)
        put("roots", JSONArray(manifest.roots))
        put("entries", JSONArray().apply {
            manifest.entries.forEach { entry ->
                put(JSONObject().apply {
                    put("path", entry.path)
                    put("bytes", entry.byteCount)
                    put("sha256", entry.sha256)
                    put("modified_at", entry.modifiedAt)
                })
            }
        })
    }.toString()

    private fun decodeManifest(encoded: String): BackupArchiveManifest {
        val root = JSONObject(encoded)
        require(root.getString("format") == FORMAT) { "This is not an Archipelago Companion backup." }
        require(root.getInt("format_version") == FORMAT_VERSION) { "This backup version is not supported." }
        val roots = root.getJSONArray("roots").let { values ->
            List(values.length()) { values.getString(it) }
        }
        val entries = root.getJSONArray("entries").let { values ->
            List(values.length()) { index ->
                val value = values.getJSONObject(index)
                val path = value.getString("path")
                if (path == PREFERENCES_PATH) validateEntryPath(path) else validateDataPath(path)
                val byteCount = value.getLong("bytes")
                val sha256 = value.getString("sha256")
                require(byteCount >= 0L && byteCount <= MAX_ENTRY_BYTES) { "Invalid backup entry size: $path" }
                require(SHA256_PATTERN.matches(sha256)) { "Invalid backup checksum: $path" }
                BackupArchiveEntry(path, byteCount, sha256, value.optLong("modified_at", 0L).coerceAtLeast(0L))
            }
        }
        return BackupArchiveManifest(
            appId = root.getString("app_id"),
            appVersion = root.optString("app_version"),
            createdAt = root.getLong("created_at"),
            roots = roots,
            entries = entries,
        )
    }

    private fun ByteArray.sha256Hex() = MessageDigest.getInstance("SHA-256").digest(this).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
