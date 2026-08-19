package eu.odran.archipelago

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.Base64

internal data class SeedFileDocumentId(val seedHistoryId: String, val fileName: String)

internal fun seedFileDocumentId(seedHistoryId: String, fileName: String): String {
    require(HISTORY_ID_PATTERN.matches(seedHistoryId)) { "Invalid seed history identifier." }
    require(fileName.isNotBlank()) { "A generated file name is required." }
    val encodedName = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(fileName.toByteArray(Charsets.UTF_8))
    return "$SEED_FILE_PREFIX$seedHistoryId:$encodedName"
}

internal fun parseSeedFileDocumentId(documentId: String): SeedFileDocumentId? = runCatching {
    if (!documentId.startsWith(SEED_FILE_PREFIX)) return@runCatching null
    val parts = documentId.removePrefix(SEED_FILE_PREFIX).split(':', limit = 2)
    if (parts.size != 2 || !HISTORY_ID_PATTERN.matches(parts[0])) return@runCatching null
    val fileName = Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8)
    fileName.takeIf { it.isNotBlank() }?.let { SeedFileDocumentId(parts[0], it) }
}.getOrNull()

private data class CompanionDocument(
    val id: String,
    val parentId: String?,
    val displayName: String,
    val mimeType: String,
    val lastModified: Long? = null,
    val size: Long? = null,
    val summary: String? = null,
    val file: File? = null,
)

/** Read-only SAF view of reusable YAMLs and generated seed artifacts. */
class CompanionDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_ROOT_PROJECTION
        return MatrixCursor(columns).apply {
            addMappedRow(
                mapOf(
                    Root.COLUMN_ROOT_ID to ROOT_ID,
                    Root.COLUMN_DOCUMENT_ID to ROOT_DOCUMENT_ID,
                    Root.COLUMN_TITLE to "Archipelago Companion",
                    Root.COLUMN_SUMMARY to "Saved YAMLs and generated seed files",
                    Root.COLUMN_FLAGS to (Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD),
                    Root.COLUMN_MIME_TYPES to SUPPORTED_MIME_TYPES.joinToString("\n"),
                    Root.COLUMN_AVAILABLE_BYTES to appContext.filesDir.usableSpace,
                ),
            )
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION
        val document = resolveDocument(documentId)
            ?: throw FileNotFoundException("Unknown Archipelago Companion document: $documentId")
        return MatrixCursor(columns).apply { addDocumentRow(document) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection?.copyOf() ?: DEFAULT_DOCUMENT_PROJECTION
        val children = when (parentDocumentId) {
            ROOT_DOCUMENT_ID -> listOfNotNull(
                resolveDocument(SAVED_YAMLS_DOCUMENT_ID),
                resolveDocument(GENERATED_SEEDS_DOCUMENT_ID),
            )
            SAVED_YAMLS_DOCUMENT_ID -> SavedYamlStore.list(appContext).mapNotNull { entry ->
                resolveSavedYaml(entry)
            }
            GENERATED_SEEDS_DOCUMENT_ID -> seedEntries().map { entry ->
                seedDirectory(entry)
            }
            else -> {
                val seedId = parentDocumentId.removePrefix(SEED_DIRECTORY_PREFIX)
                    .takeIf { parentDocumentId.startsWith(SEED_DIRECTORY_PREFIX) }
                    ?: throw FileNotFoundException("Document is not a directory: $parentDocumentId")
                val entry = seedEntries().firstOrNull { it.id == seedId }
                    ?: throw FileNotFoundException("Unknown generated seed: $seedId")
                SeedHistoryStore.documentFiles(appContext, entry.id).map { storedFile ->
                    seedFile(entry, storedFile)
                }
            }
        }
        return MatrixCursor(columns).apply {
            children.forEach { document -> addDocumentRow(document) }
            setNotificationUri(
                appContext.contentResolver,
                DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId),
            )
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Archipelago Companion documents are read-only.")
        signal?.throwIfCanceled()
        val document = resolveDocument(documentId)
            ?: throw FileNotFoundException("Unknown Archipelago Companion document: $documentId")
        val file = document.file?.takeIf { it.isFile }
            ?: throw FileNotFoundException("Document is not a readable file: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == documentId) return false
        var current = resolveDocument(documentId) ?: return false
        while (current.parentId != null) {
            if (current.parentId == parentDocumentId) return true
            current = resolveDocument(current.parentId) ?: return false
        }
        return false
    }

    private fun resolveDocument(documentId: String): CompanionDocument? = when {
        documentId == ROOT_DOCUMENT_ID -> CompanionDocument(
            id = ROOT_DOCUMENT_ID,
            parentId = null,
            displayName = "Archipelago Companion",
            mimeType = Document.MIME_TYPE_DIR,
        )
        documentId == SAVED_YAMLS_DOCUMENT_ID -> CompanionDocument(
            id = SAVED_YAMLS_DOCUMENT_ID,
            parentId = ROOT_DOCUMENT_ID,
            displayName = "Saved YAMLs",
            mimeType = Document.MIME_TYPE_DIR,
        )
        documentId == GENERATED_SEEDS_DOCUMENT_ID -> CompanionDocument(
            id = GENERATED_SEEDS_DOCUMENT_ID,
            parentId = ROOT_DOCUMENT_ID,
            displayName = "Generated seeds",
            mimeType = Document.MIME_TYPE_DIR,
        )
        documentId.startsWith(SAVED_YAML_PREFIX) -> {
            val id = documentId.removePrefix(SAVED_YAML_PREFIX)
            SavedYamlStore.list(appContext).firstOrNull { it.id == id }?.let(::resolveSavedYaml)
        }
        documentId.startsWith(SEED_DIRECTORY_PREFIX) -> {
            val id = documentId.removePrefix(SEED_DIRECTORY_PREFIX)
            seedEntries().firstOrNull { it.id == id }?.let(::seedDirectory)
        }
        documentId.startsWith(SEED_FILE_PREFIX) -> {
            val parsed = parseSeedFileDocumentId(documentId) ?: return null
            val entry = seedEntries().firstOrNull { it.id == parsed.seedHistoryId }
                ?: return null
            SeedHistoryStore.documentFiles(appContext, entry.id)
                .firstOrNull { it.name == parsed.fileName }
                ?.let { seedFile(entry, it) }
        }
        else -> null
    }

    private fun resolveSavedYaml(entry: SavedYamlEntry): CompanionDocument? {
        val file = SavedYamlStore.documentFile(appContext, entry.id) ?: return null
        return CompanionDocument(
            id = "$SAVED_YAML_PREFIX${entry.id}",
            parentId = SAVED_YAMLS_DOCUMENT_ID,
            displayName = yamlDisplayName(entry.name),
            mimeType = YAML_MIME_TYPE,
            lastModified = entry.createdAt,
            size = file.length(),
            summary = entry.players.takeIf { it.isNotEmpty() }
                ?.joinToString { player -> "${player.name} · ${player.game}" },
            file = file,
        )
    }

    private fun seedDirectory(entry: SeedHistoryEntry) = CompanionDocument(
        id = "$SEED_DIRECTORY_PREFIX${entry.id}",
        parentId = GENERATED_SEEDS_DOCUMENT_ID,
        displayName = "Seed ${entry.seed.ifBlank { entry.id.take(12) }}",
        mimeType = Document.MIME_TYPE_DIR,
        lastModified = entry.createdAt,
        summary = entry.players.joinToString().takeIf { it.isNotBlank() },
    )

    private fun seedEntries(): List<SeedHistoryEntry> =
        SeedHistoryStore.list(appContext).filter { HISTORY_ID_PATTERN.matches(it.id) }

    private fun seedFile(entry: SeedHistoryEntry, storedFile: SeedHistoryDocumentFile) = CompanionDocument(
        id = seedFileDocumentId(entry.id, storedFile.name),
        parentId = "$SEED_DIRECTORY_PREFIX${entry.id}",
        displayName = storedFile.name,
        mimeType = mimeType(storedFile.name),
        lastModified = storedFile.file.lastModified().takeIf { it > 0 } ?: entry.createdAt,
        size = storedFile.file.length(),
        file = storedFile.file,
    )

    private fun MatrixCursor.addDocumentRow(document: CompanionDocument) {
        addMappedRow(
            mapOf(
                Document.COLUMN_DOCUMENT_ID to document.id,
                Document.COLUMN_DISPLAY_NAME to document.displayName,
                Document.COLUMN_MIME_TYPE to document.mimeType,
                Document.COLUMN_LAST_MODIFIED to document.lastModified,
                Document.COLUMN_SIZE to document.size,
                Document.COLUMN_SUMMARY to document.summary,
                Document.COLUMN_FLAGS to if (document.mimeType == Document.MIME_TYPE_DIR) {
                    Document.FLAG_DIR_PREFERS_LAST_MODIFIED
                } else {
                    0
                },
            ),
        )
    }

    private fun MatrixCursor.addMappedRow(values: Map<String, Any?>) {
        addRow(columnNames.map { column -> values[column] }.toTypedArray())
    }

    private val appContext: Context
        get() = requireNotNull(context).applicationContext

    private val authority: String
        get() = "${appContext.packageName}.documents"

    companion object {
        private const val ROOT_ID = "archipelago-companion"
        private const val ROOT_DOCUMENT_ID = "root"
        internal const val SAVED_YAMLS_DOCUMENT_ID = "saved-yamls"
        internal const val GENERATED_SEEDS_DOCUMENT_ID = "generated-seeds"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_SUMMARY,
        )
        private val SUPPORTED_MIME_TYPES = listOf(
            YAML_MIME_TYPE,
            "application/zip",
            "application/json",
            "application/octet-stream",
            "text/plain",
        )

        fun notifySavedYamlsChanged(context: Context) = notifyChildrenChanged(context, SAVED_YAMLS_DOCUMENT_ID)

        fun notifyGeneratedSeedsChanged(context: Context) =
            notifyChildrenChanged(context, GENERATED_SEEDS_DOCUMENT_ID)

        private fun notifyChildrenChanged(context: Context, parentDocumentId: String) {
            runCatching {
                val authority = "${context.packageName}.documents"
                context.contentResolver.notifyChange(
                    DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId),
                    null,
                )
            }
        }
    }
}

internal fun yamlDisplayName(name: String): String {
    val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Players" }
    return if (safeName.endsWith(".yaml", true) || safeName.endsWith(".yml", true)) {
        safeName
    } else {
        "$safeName.yaml"
    }
}

internal fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "yaml", "yml" -> YAML_MIME_TYPE
    "zip" -> "application/zip"
    "json" -> "application/json"
    "txt", "log" -> "text/plain"
    else -> "application/octet-stream"
}

private const val YAML_MIME_TYPE = "application/yaml"
private const val SAVED_YAML_PREFIX = "saved-yaml:"
private const val SEED_DIRECTORY_PREFIX = "seed:"
private const val SEED_FILE_PREFIX = "seed-file:"
private val HISTORY_ID_PATTERN = Regex("[0-9]+_[a-f0-9]{8}")
