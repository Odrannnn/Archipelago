package eu.odran.archipelago

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.security.MessageDigest

internal data class InstalledCoreState(
    val installedFileName: String? = null,
    val installedSha256: String? = null,
    val matchesAvailable: Boolean = false,
)

/** Read/write access to RetroArch's private core directory through its SAF provider. */
internal object RetroArchCoreStore {
    private const val AUTHORITY = "com.retroarch.aarch64.documents"
    private const val PREFERENCES = "retroarch_core_tree"
    private const val KEY_TREE_URI = "tree_uri"

    fun selectedTree(context: Context): Uri? {
        val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        if (permission?.isReadPermission != true || permission.isWritePermission != true) {
            forget(context)
            return null
        }
        val isUsable = runCatching {
            uri.authority == AUTHORITY && folderName(context, uri).equals("cores", true)
        }.getOrDefault(false)
        return if (isUsable) uri else {
            forget(context)
            null
        }
    }

    fun storeSelection(context: Context, uri: Uri, resultFlags: Int) {
        require(uri.authority == AUTHORITY) { "Select the cores folder under the RetroArch storage location." }
        val permissionFlags = resultFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(permissionFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
            permissionFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
        ) { "RetroArch core access must include both read and write permission." }
        context.contentResolver.takePersistableUriPermission(uri, permissionFlags)
        try {
            require(folderName(context, uri).equals("cores", ignoreCase = true)) {
                "Select RetroArch's cores folder, not its top-level storage folder."
            }
        } catch (error: Throwable) {
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, permissionFlags) }
            throw error
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
    }

    fun forget(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(KEY_TREE_URI, null)?.let { stored ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(stored),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        preferences.edit().remove(KEY_TREE_URI).apply()
    }

    fun installedState(context: Context, asset: ComponentAsset): InstalledCoreState {
        require(asset.component.kind == ComponentKind.CORE)
        val tree = selectedTree(context) ?: return InstalledCoreState()
        val entries = children(context, tree)
        val exact = entries.firstOrNull { it.displayName == asset.fileName }
        val installed = exact ?: entries.firstOrNull {
            asset.component.coreFamilyPattern?.matches(it.displayName) == true
        }
        val digest = installed?.let { sha256(context, it.uri) }
        return InstalledCoreState(
            installed?.displayName,
            digest,
            exact != null && digest.equals(asset.sha256, ignoreCase = true),
        )
    }

    fun install(context: Context, asset: ComponentAsset, verifiedFile: File) {
        require(asset.component.kind == ComponentKind.CORE)
        require(verifiedFile.length() == asset.byteCount) { "The downloaded core has the wrong size." }
        require(verifiedFile.sha256Hex() == asset.sha256) { "The downloaded core failed SHA-256 verification." }
        val tree = checkNotNull(selectedTree(context)) { "Choose RetroArch's cores folder first." }
        val resolver = context.contentResolver
        val finalName = asset.fileName
        val temporaryName = ".$finalName.apupdate"
        val backupName = ".$finalName.apbackup"

        var entries = children(context, tree)
        var current = entries.firstOrNull { it.displayName == finalName }
        val existingBackup = entries.firstOrNull { it.displayName == backupName }
        if (current == null && existingBackup != null) {
            DocumentsContract.renameDocument(resolver, existingBackup.uri, finalName)
                ?: error("Could not recover the previous RetroArch core backup.")
            entries = children(context, tree)
            current = entries.firstOrNull { it.displayName == finalName }
        } else if (current != null && existingBackup != null) {
            DocumentsContract.deleteDocument(resolver, existingBackup.uri)
        }
        children(context, tree)
            .filter { it.displayName.startsWith(temporaryName) }
            .forEach { DocumentsContract.deleteDocument(resolver, it.uri) }

        val temporaryUri = checkNotNull(
            DocumentsContract.createDocument(resolver, treeDocumentUri(tree), "application/octet-stream", temporaryName),
        ) { "RetroArch did not create the temporary core document." }
        try {
            resolver.openOutputStream(temporaryUri, "rwt")?.use { output ->
                verifiedFile.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: error("Could not write to RetroArch's cores folder.")
            require(sha256(context, temporaryUri) == asset.sha256) {
                "The core changed while it was copied into RetroArch storage."
            }

            val backupUri = current?.let {
                DocumentsContract.renameDocument(resolver, it.uri, backupName)
                    ?: error("Could not preserve the previous RetroArch core.")
            }
            try {
                DocumentsContract.renameDocument(resolver, temporaryUri, finalName)
                    ?: error("Could not activate the new RetroArch core.")
            } catch (error: Throwable) {
                backupUri?.let { runCatching { DocumentsContract.renameDocument(resolver, it, finalName) } }
                throw error
            }
            backupUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, temporaryUri) }
            throw error
        }
    }

    private data class CoreDocument(val uri: Uri, val displayName: String)

    private fun children(context: Context, tree: Uri): List<CoreDocument> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        return context.contentResolver.query(
            childUri,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CoreDocument(
                            DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn)),
                            cursor.getString(nameColumn),
                        ),
                    )
                }
            }
        } ?: error("Could not read RetroArch's cores folder.")
    }

    private fun folderName(context: Context, tree: Uri): String? {
        val documentUri = treeDocumentUri(tree)
        return context.contentResolver.query(
            documentUri,
            arrayOf(Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun treeDocumentUri(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        tree,
        DocumentsContract.getTreeDocumentId(tree),
    )

    private fun sha256(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: error("Could not read ${uri.lastPathSegment ?: "the RetroArch core"}.")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
