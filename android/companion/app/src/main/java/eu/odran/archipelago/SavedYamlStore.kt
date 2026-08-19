package eu.odran.archipelago

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedYamlEntry(
    val id: String,
    val name: String,
    val createdAt: Long,
    val byteCount: Int,
)

internal fun orderedSavedYamlEntries(entries: List<SavedYamlEntry>): List<SavedYamlEntry> =
    entries.sortedWith(compareByDescending<SavedYamlEntry> { it.createdAt }.thenBy { it.id })

/** Stores reusable player YAML configurations in app-private files. */
object SavedYamlStore {
    private const val INDEX_VERSION = 1
    const val MAX_YAML_BYTES = 2 * 1024 * 1024
    private val lock = Any()
    private val idPattern = Regex("[0-9]+_[a-f0-9]{8}")

    fun list(context: Context): List<SavedYamlEntry> = synchronized(lock) {
        orderedSavedYamlEntries(readIndex(context).filter { yamlFile(context, it.id).isFile })
    }

    fun save(context: Context, name: String, yaml: String): SavedYamlEntry = synchronized(lock) {
        val cleanName = validateName(name)
        val bytes = yaml.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty()) { "The player YAML is empty." }
        require(bytes.size <= MAX_YAML_BYTES) {
            "The player YAML is larger than ${MAX_YAML_BYTES / 1024} KiB."
        }

        val createdAt = System.currentTimeMillis()
        val id = "${createdAt}_${UUID.randomUUID().toString().take(8)}"
        val entry = SavedYamlEntry(id, cleanName, createdAt, bytes.size)
        val file = yamlFile(context, id)
        file.writeBytes(bytes)
        runCatching { writeIndex(context, readIndex(context) + entry) }
            .onFailure {
                file.delete()
                throw it
            }
        CompanionDocumentsProvider.notifySavedYamlsChanged(context)
        entry
    }

    fun read(context: Context, id: String): String = synchronized(lock) {
        require(idPattern.matches(id)) { "Invalid saved YAML identifier." }
        val entry = readIndex(context).firstOrNull { it.id == id }
            ?: error("The saved YAML is no longer available.")
        val file = yamlFile(context, entry.id)
        require(file.isFile && file.length() in 1..MAX_YAML_BYTES.toLong()) {
            "The saved YAML file is empty or too large."
        }
        val bytes = file.readBytes()
        bytes.toString(Charsets.UTF_8)
    }

    fun delete(context: Context, id: String): Boolean = synchronized(lock) {
        if (!idPattern.matches(id)) return false
        val entries = readIndex(context)
        if (entries.none { it.id == id }) return false
        val file = yamlFile(context, id)
        if (file.exists() && !file.delete()) return false
        writeIndex(context, entries.filterNot { it.id == id })
        CompanionDocumentsProvider.notifySavedYamlsChanged(context)
        true
    }

    internal fun documentFile(context: Context, id: String): File? = synchronized(lock) {
        if (!idPattern.matches(id) || readIndex(context).none { it.id == id }) return@synchronized null
        val root = storageRoot(context).canonicalFile
        val file = File(root, "$id.yaml").canonicalFile
        file.takeIf {
            it.parentFile == root && it.isFile && it.length() in 1..MAX_YAML_BYTES.toLong()
        }
    }

    private fun validateName(name: String): String {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Enter a name for the saved YAML." }
        require(cleanName.length <= 100) { "The saved YAML name is too long." }
        require(cleanName.none { it.isISOControl() }) { "The saved YAML name contains invalid characters." }
        return cleanName
    }

    private fun storageRoot(context: Context) = File(context.filesDir, "saved_yamls").apply { mkdirs() }

    private fun yamlFile(context: Context, id: String) = File(storageRoot(context), "$id.yaml")

    private fun indexFile(context: Context) = File(storageRoot(context), "index.json")

    private fun readIndex(context: Context): List<SavedYamlEntry> = runCatching {
        val file = indexFile(context)
        if (!file.isFile) return@runCatching emptyList()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        require(root.optInt("version") == INDEX_VERSION) { "Unsupported saved YAML index version." }
        val entries = root.getJSONArray("entries")
        List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            SavedYamlEntry(
                id = item.getString("id"),
                name = item.getString("name"),
                createdAt = item.getLong("created_at"),
                byteCount = item.getInt("byte_count"),
            ).also { entry ->
                require(idPattern.matches(entry.id)) { "Invalid saved YAML entry." }
                validateName(entry.name)
                require(entry.createdAt > 0 && entry.byteCount in 1..MAX_YAML_BYTES) {
                    "Invalid saved YAML metadata."
                }
            }
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    private fun writeIndex(context: Context, entries: List<SavedYamlEntry>) {
        val root = JSONObject().apply {
            put("version", INDEX_VERSION)
            put("entries", JSONArray().apply {
                orderedSavedYamlEntries(entries).forEach { entry ->
                    put(JSONObject().apply {
                        put("id", entry.id)
                        put("name", entry.name)
                        put("created_at", entry.createdAt)
                        put("byte_count", entry.byteCount)
                    })
                }
            })
        }
        val target = indexFile(context)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(root.toString(2), Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }
}
