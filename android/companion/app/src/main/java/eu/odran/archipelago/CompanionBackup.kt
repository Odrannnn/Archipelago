package eu.odran.archipelago

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.UUID

data class BackupCategorySummary(
    val id: String,
    val label: String,
    val fileCount: Int,
    val byteCount: Long,
)

data class CompanionBackupSummary(
    val fileCount: Int,
    val byteCount: Long,
    val categories: List<BackupCategorySummary>,
    val createdAt: Long,
)

class CompanionBackupRollbackException(message: String, cause: Throwable) : IllegalStateException(message, cause)

/** Maps portable app state to the validated backup archive. */
object CompanionBackup {
    private data class RootSpec(
        val id: String,
        val label: String,
        val target: (Context) -> File,
    )

    private val rootSpecs = listOf(
        RootSpec("offline_generator", "APWorlds and generated seeds") { File(it.filesDir, "offline_generator") },
        RootSpec("saved_yamls", "Saved YAMLs") { File(it.filesDir, "saved_yamls") },
        RootSpec("patched_roms", "Patched ROMs") { File(it.filesDir, "patched_roms") },
        RootSpec("base_rom", "Cached base ROMs") { File(it.noBackupFilesDir, "base_rom") },
    )
    private val lock = Any()

    fun inventory(context: Context): CompanionBackupSummary = synchronized(lock) {
        val categories = rootSpecs.map { spec ->
            val files = collectFiles(spec.target(context), spec.id)
            BackupCategorySummary(spec.id, spec.label, files.size, files.sumOf { it.file.length() })
        }
        CompanionBackupSummary(
            fileCount = categories.sumOf { it.fileCount },
            byteCount = categories.sumOf { it.byteCount },
            categories = categories,
            createdAt = System.currentTimeMillis(),
        )
    }

    fun export(context: Context, output: OutputStream): CompanionBackupSummary = synchronized(lock) {
        val createdAt = System.currentTimeMillis()
        val byCategory = rootSpecs.associateWith { spec -> collectFiles(spec.target(context), spec.id) }
        val sources = byCategory.values.flatten()
        BackupArchive.write(
            output = output,
            appId = context.packageName,
            appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
            createdAt = createdAt,
            sources = sources,
            preferences = PreferenceBackupCodec.encode(context),
        )
        val categories = rootSpecs.map { spec ->
            val files = byCategory.getValue(spec)
            BackupCategorySummary(spec.id, spec.label, files.size, files.sumOf { it.file.length() })
        }
        CompanionBackupSummary(
            fileCount = sources.size,
            byteCount = sources.sumOf { it.file.length() },
            categories = categories,
            createdAt = createdAt,
        )
    }

    fun restore(
        context: Context,
        input: InputStream,
        beforeReplace: () -> Unit = {},
    ): CompanionBackupSummary = synchronized(lock) {
        val operationId = UUID.randomUUID().toString()
        val stagingRoot = File(context.cacheDir, "companion-restore-$operationId")
        val rollbackRoot = File(context.cacheDir, "companion-rollback-$operationId")
        var preserveRollback = false
        try {
            val extracted = BackupArchive.extract(input, stagingRoot, context.packageName)
            val restoredPreferences = PreferenceBackupCodec.decode(extracted.preferences)
            val originalPreferences = PreferenceBackupCodec.decode(PreferenceBackupCodec.encode(context))
            beforeReplace()
            val completed = mutableListOf<RootSpec>()
            require(rollbackRoot.mkdirs()) { "Could not create restore rollback storage." }

            try {
                rootSpecs.forEach { spec ->
                    val target = checkedTarget(context, spec)
                    val rollback = File(rollbackRoot, spec.id)
                    if (target.exists()) {
                        require(target.renameTo(rollback)) { "Could not prepare ${spec.label} for restore." }
                    }
                    completed += spec
                    val staged = File(stagingRoot, "data/${spec.id}")
                    if (staged.exists()) {
                        target.parentFile?.mkdirs()
                        require(staged.renameTo(target)) { "Could not restore ${spec.label}." }
                    }
                }
                PreferenceBackupCodec.apply(context, restoredPreferences)
                SeedHistoryStore.repairRestoredPaths(context)
            } catch (error: Throwable) {
                val rollbackFailures = mutableListOf<String>()
                runCatching { PreferenceBackupCodec.apply(context, originalPreferences) }
                    .onFailure { rollbackFailures += "settings" }
                completed.asReversed().forEach { spec ->
                    val target = checkedTarget(context, spec)
                    if (!deleteTreeWithoutFollowingLinks(target)) rollbackFailures += spec.label
                    val rollback = File(rollbackRoot, spec.id)
                    if (rollback.exists() && !rollback.renameTo(target)) {
                        rollbackFailures += spec.label
                    }
                }
                if (rollbackFailures.isNotEmpty()) {
                    preserveRollback = true
                    throw CompanionBackupRollbackException(
                        "Restore failed and some previous data could not be put back (${rollbackFailures.distinct().joinToString()}). " +
                            "Recovery data was preserved in app storage.",
                        error,
                    )
                }
                throw error
            }

            CompanionDocumentsProvider.notifySavedYamlsChanged(context)
            CompanionDocumentsProvider.notifyGeneratedSeedsChanged(context)
            val categories = rootSpecs.map { spec ->
                val files = collectFiles(spec.target(context), spec.id)
                BackupCategorySummary(spec.id, spec.label, files.size, files.sumOf { it.file.length() })
            }
            CompanionBackupSummary(
                fileCount = categories.sumOf { it.fileCount },
                byteCount = categories.sumOf { it.byteCount },
                categories = categories,
                createdAt = extracted.manifest.createdAt,
            )
        } finally {
            deleteTreeWithoutFollowingLinks(stagingRoot)
            if (!preserveRollback) deleteTreeWithoutFollowingLinks(rollbackRoot)
        }
    }

    private fun collectFiles(root: File, rootId: String): List<BackupArchiveSource> {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
        val canonicalRoot = root.canonicalFile
        val result = mutableListOf<BackupArchiveSource>()

        fun visit(directory: File, relativeParent: String) {
            directory.listFiles()?.sortedBy { it.name }?.forEach { child ->
                if (Files.isSymbolicLink(child.toPath())) return@forEach
                val relative = if (relativeParent.isBlank()) child.name else "$relativeParent/${child.name}"
                if (isTransient(relative, child)) return@forEach
                val canonical = child.canonicalFile
                require(canonical == canonicalRoot || canonical.path.startsWith(canonicalRoot.path + File.separator)) {
                    "App storage contains an unsafe path: $relative"
                }
                when {
                    child.isDirectory -> visit(child, relative)
                    child.isFile -> result += BackupArchiveSource("data/$rootId/$relative", child)
                }
            }
        }
        visit(root, "")
        return result
    }

    private fun isTransient(relative: String, file: File): Boolean {
        val segments = relative.split('/')
        if (segments.any { it == "__pycache__" }) return true
        if (relative == "rom-inputs" || relative.startsWith("rom-inputs/")) return true
        return file.name.endsWith(".pyc", ignoreCase = true) || file.name.endsWith(".tmp", ignoreCase = true)
    }

    private fun checkedTarget(context: Context, spec: RootSpec): File {
        val target = spec.target(context).canonicalFile
        val expectedParent = when (spec.id) {
            "base_rom" -> context.noBackupFilesDir.canonicalFile
            else -> context.filesDir.canonicalFile
        }
        require(target.parentFile == expectedParent) { "Unsafe restore destination for ${spec.label}." }
        return target
    }

    private fun deleteTreeWithoutFollowingLinks(file: File): Boolean {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return true
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
            file.listFiles()?.forEach { child ->
                if (!deleteTreeWithoutFollowingLinks(child)) return false
            }
        }
        return file.delete()
    }
}

private data class StoredPreference(val type: String, val value: Any)
private typealias PreferenceSnapshot = Map<String, Map<String, StoredPreference>>

/** Typed SharedPreferences codec. URI grants are intentionally excluded because Android cannot transfer them. */
private object PreferenceBackupCodec {
    private const val VERSION = 1
    private val preferenceNames = listOf(
        "archipelago_web_host",
        "archipelago_server",
        "joined_archipelago_room",
        "hosted_room_history_links",
        "generator_draft",
        "last_active_retroarch_rom",
    )

    fun encode(context: Context): ByteArray {
        val stores = JSONObject()
        preferenceNames.forEach { name ->
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val encodedValues = JSONObject()
            values.toSortedMap().forEach { (key, value) -> encodedValues.put(key, encodeValue(value)) }
            stores.put(name, encodedValues)
        }
        return JSONObject().apply {
            put("version", VERSION)
            put("stores", stores)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(encoded: ByteArray): PreferenceSnapshot {
        val root = JSONObject(encoded.toString(Charsets.UTF_8))
        require(root.getInt("version") == VERSION) { "Backup settings version is not supported." }
        val stores = root.getJSONObject("stores")
        require(stores.keys().asSequence().toSet() == preferenceNames.toSet()) {
            "Backup settings contain an unexpected store."
        }
        return preferenceNames.associateWith { name ->
            val values = stores.getJSONObject(name)
            values.keys().asSequence().associateWith { key -> decodeValue(values.getJSONObject(key)) }
        }
    }

    fun apply(context: Context, snapshot: PreferenceSnapshot) {
        require(snapshot.keys == preferenceNames.toSet()) { "Backup settings are incomplete." }
        preferenceNames.forEach { name ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            snapshot.getValue(name).forEach { (key, stored) -> editor.putStored(key, stored) }
            require(editor.commit()) { "Could not restore $name settings." }
        }
    }

    private fun encodeValue(value: Any?): JSONObject = JSONObject().apply {
        when (value) {
            is String -> { put("type", "string"); put("value", value) }
            is Int -> { put("type", "int"); put("value", value) }
            is Long -> { put("type", "long"); put("value", value) }
            is Float -> { put("type", "float"); put("value", value.toDouble()) }
            is Boolean -> { put("type", "boolean"); put("value", value) }
            is Set<*> -> {
                require(value.all { it is String }) { "Unsupported preference string set." }
                put("type", "string_set")
                put("value", JSONArray(value.filterIsInstance<String>().sorted()))
            }
            else -> error("Unsupported preference value type: ${value?.javaClass?.simpleName ?: "null"}")
        }
    }

    private fun decodeValue(encoded: JSONObject): StoredPreference {
        return when (val type = encoded.getString("type")) {
            "string" -> StoredPreference(type, encoded.getString("value"))
            "int" -> StoredPreference(type, encoded.getInt("value"))
            "long" -> StoredPreference(type, encoded.getLong("value"))
            "float" -> StoredPreference(type, encoded.getDouble("value").toFloat())
            "boolean" -> StoredPreference(type, encoded.getBoolean("value"))
            "string_set" -> {
                val values = encoded.getJSONArray("value")
                StoredPreference(type, List(values.length()) { values.getString(it) }.toSet())
            }
            else -> error("Unsupported preference value type: $type")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun SharedPreferences.Editor.putStored(key: String, stored: StoredPreference) {
        when (stored.type) {
            "string" -> putString(key, stored.value as String)
            "int" -> putInt(key, stored.value as Int)
            "long" -> putLong(key, stored.value as Long)
            "float" -> putFloat(key, stored.value as Float)
            "boolean" -> putBoolean(key, stored.value as Boolean)
            "string_set" -> putStringSet(key, stored.value as Set<String>)
            else -> error("Unsupported preference value type: ${stored.type}")
        }
    }
}
