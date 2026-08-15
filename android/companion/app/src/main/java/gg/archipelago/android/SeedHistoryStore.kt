package gg.archipelago.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SeedHistoryEntry(
    val id: String,
    val seed: String,
    val createdAt: Long,
    val yaml: String,
    val players: List<String>,
    val files: List<GeneratedArtifact>,
    val patches: List<GeneratedArtifact>,
)

/** Persistent app-private storage for generated seed packages and their source YAML. */
object SeedHistoryStore {
    private const val INDEX_VERSION = 1
    private val lock = Any()

    fun list(context: Context): List<SeedHistoryEntry> = synchronized(lock) {
        readIndex(context).sortedByDescending { it.createdAt }
    }

    fun add(
        context: Context,
        result: GenerationResult,
        yaml: String,
    ): SeedHistoryEntry = synchronized(lock) {
        val createdAt = System.currentTimeMillis()
        val id = "${createdAt}_${UUID.randomUUID().toString().take(8)}"
        val entryDirectory = File(historyRoot(context), id).apply { mkdirs() }
        File(entryDirectory, "Players.yaml").writeText(yaml)

        val sourceArtifacts = (result.files + result.patches).distinctBy { it.path }
        val copiedByPath = sourceArtifacts.associate { artifact ->
            val source = File(artifact.path)
            require(source.isFile) { "Generated artifact is missing: ${artifact.name}" }
            val safeName = File(artifact.name).name
            val destination = uniqueDestination(entryDirectory, safeName)
            source.copyTo(destination)
            artifact.path to GeneratedArtifact(destination.name, destination.absolutePath, artifact.kind)
        }

        fun copied(artifacts: List<GeneratedArtifact>): List<GeneratedArtifact> = artifacts.mapNotNull {
            copiedByPath[it.path]
        }

        val entry = SeedHistoryEntry(
            id = id,
            seed = result.seed,
            createdAt = createdAt,
            yaml = yaml,
            players = result.players,
            files = copied(result.files),
            patches = copied(result.patches),
        )
        val entries = readIndex(context).filterNot { it.id == id } + entry
        writeIndex(context, entries)
        entry
    }

    fun delete(context: Context, id: String) = synchronized(lock) {
        val entries = readIndex(context)
        val entry = entries.firstOrNull { it.id == id } ?: return@synchronized
        entryDirectory(context, entry).deleteRecursively()
        writeIndex(context, entries.filterNot { it.id == id })
    }

    private fun historyRoot(context: Context): File =
        File(context.filesDir, "offline_generator/seed_history").apply { mkdirs() }

    private fun indexFile(context: Context) = File(historyRoot(context), "index.json")

    private fun entryDirectory(context: Context, entry: SeedHistoryEntry): File {
        val root = historyRoot(context).canonicalFile
        val directory = File(root, entry.id).canonicalFile
        require(directory.parentFile == root) { "Invalid seed history entry path" }
        return directory
    }

    private fun uniqueDestination(directory: File, requestedName: String): File {
        var destination = File(directory, requestedName)
        var suffix = 2
        while (destination.exists()) {
            val extension = destination.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
            val base = requestedName.removeSuffix(extension)
            destination = File(directory, "${base}_$suffix$extension")
            suffix += 1
        }
        return destination
    }

    private fun readIndex(context: Context): List<SeedHistoryEntry> {
        val file = indexFile(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            require(root.optInt("version", INDEX_VERSION) == INDEX_VERSION) {
                "Unsupported seed history version"
            }
            val entries = root.optJSONArray("entries") ?: JSONArray()
            List(entries.length()) { index -> parseEntry(entries.getJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, entries: List<SeedHistoryEntry>) {
        val root = JSONObject().apply {
            put("version", INDEX_VERSION)
            put("entries", JSONArray().apply { entries.forEach { put(entryJson(it)) } })
        }
        val destination = indexFile(context)
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(root.toString())
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    private fun entryJson(entry: SeedHistoryEntry) = JSONObject().apply {
        put("id", entry.id)
        put("seed", entry.seed)
        put("createdAt", entry.createdAt)
        put("yaml", entry.yaml)
        put("players", JSONArray(entry.players))
        put("files", artifactsJson(entry.files))
        put("patches", artifactsJson(entry.patches))
    }

    private fun artifactsJson(artifacts: List<GeneratedArtifact>) = JSONArray().apply {
        artifacts.forEach { artifact ->
            put(JSONObject().apply {
                put("name", artifact.name)
                put("path", artifact.path)
                put("kind", artifact.kind)
            })
        }
    }

    private fun parseEntry(root: JSONObject) = SeedHistoryEntry(
        id = root.getString("id"),
        seed = root.getString("seed"),
        createdAt = root.getLong("createdAt"),
        yaml = root.getString("yaml"),
        players = root.getJSONArray("players").toStringList(),
        files = root.getJSONArray("files").toArtifacts(),
        patches = root.getJSONArray("patches").toArtifacts(),
    )

    private fun JSONArray.toStringList() = List(length()) { getString(it) }

    private fun JSONArray.toArtifacts() = List(length()) { index ->
        val artifact = getJSONObject(index)
        GeneratedArtifact(
            artifact.getString("name"),
            artifact.getString("path"),
            artifact.getString("kind"),
        )
    }
}
