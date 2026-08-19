package eu.odran.archipelago

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class DolphinIniUpdate(
    val content: String,
    val previousPort: Int?,
)

data class DolphinIniApplyResult(
    val changed: Boolean,
    val previousPort: Int?,
)

/** Manages the one Dolphin.ini document explicitly granted through Android's file picker. */
object DolphinIniManager {
    const val DISABLED_GDB_PORT = -1
    private const val PREFS = "dolphin_bridge"
    private const val DOCUMENT_URI = "dolphin_ini_uri"
    private const val MAX_INI_BYTES = 2 * 1024 * 1024
    private const val MAX_BACKUPS = 3
    private val utf8Bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    private val sectionPattern = Regex("^\\s*\\[([^]]+)]\\s*(?:[;#].*)?$")
    private val portPattern = Regex(
        "^([\\t ]*)GDBPort([\\t ]*)=([\\t ]*)([^;#]*)(.*)$",
        RegexOption.IGNORE_CASE,
    )

    fun linkedUri(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(DOCUMENT_URI, null)
        ?.let(Uri::parse)

    fun applySelected(context: Context, uri: Uri, resultFlags: Int, port: Int): DolphinIniApplyResult {
        requireDolphinIniName(context, uri)
        val permissionFlags = resultFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(permissionFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            "The file picker did not grant read access to Dolphin.ini."
        }
        require(permissionFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
            "The file picker did not grant write access to Dolphin.ini."
        }
        context.contentResolver.takePersistableUriPermission(uri, permissionFlags)
        val result = applyPort(context, uri, port)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(DOCUMENT_URI, uri.toString())
            .apply()
        return result
    }

    fun applyLinked(context: Context, port: Int): DolphinIniApplyResult {
        val uri = linkedUri(context) ?: error("Select Dolphin.ini first.")
        requireDolphinIniName(context, uri)
        return applyPort(context, uri, port)
    }

    fun forgetLinked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(DOCUMENT_URI)
            .apply()
    }

    internal fun updateGdbPort(content: String, port: Int): DolphinIniUpdate {
        require(port == DISABLED_GDB_PORT || port in 1..65535) {
            "Dolphin GDB port must be -1 (disabled) or between 1 and 65535"
        }
        val newline = if (content.contains("\r\n")) "\r\n" else "\n"
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val hadTrailingNewline = normalized.endsWith('\n')
        val body = if (hadTrailingNewline) normalized.dropLast(1) else normalized
        val lines = if (body.isEmpty()) mutableListOf() else body.split('\n').toMutableList()

        var currentSection: String? = null
        var firstGeneralSection = -1
        val portLines = mutableListOf<Int>()
        lines.forEachIndexed { index, line ->
            val section = sectionPattern.matchEntire(line)?.groupValues?.get(1)?.trim()
            if (section != null) {
                currentSection = section
                if (firstGeneralSection < 0 && section.equals("General", ignoreCase = true)) {
                    firstGeneralSection = index
                }
            } else if (currentSection.equals("General", ignoreCase = true) && portPattern.matches(line)) {
                portLines += index
            }
        }

        val previousPort = portLines.firstOrNull()?.let { index ->
            portPattern.matchEntire(lines[index])?.groupValues?.get(4)?.trim()?.toIntOrNull()
        }
        if (portLines.isNotEmpty()) {
            val first = portLines.first()
            val match = checkNotNull(portPattern.matchEntire(lines[first]))
            val valueSuffix = match.groupValues[4].takeLastWhile { it == ' ' || it == '\t' }
            lines[first] = buildString {
                append(match.groupValues[1])
                append("GDBPort")
                append(match.groupValues[2])
                append('=')
                append(match.groupValues[3])
                append(port)
                append(valueSuffix)
                append(match.groupValues[5])
            }
            portLines.drop(1).asReversed().forEach(lines::removeAt)
        } else if (firstGeneralSection >= 0) {
            val nextSection = (firstGeneralSection + 1 until lines.size).firstOrNull { index ->
                sectionPattern.matches(lines[index])
            } ?: lines.size
            lines.add(nextSection, "GDBPort = $port")
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "[General]"
            lines += "GDBPort = $port"
        }

        val updated = lines.joinToString(newline) +
            if (hadTrailingNewline || content.isEmpty()) newline else ""
        return DolphinIniUpdate(updated, previousPort)
    }

    private fun applyPort(context: Context, uri: Uri, port: Int): DolphinIniApplyResult {
        val original = context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readAtMost(MAX_INI_BYTES + 1)
            require(bytes.size <= MAX_INI_BYTES) { "Dolphin.ini is unexpectedly large." }
            bytes
        } ?: error("Could not open Dolphin.ini for reading.")
        val hasBom = original.size >= utf8Bom.size &&
            original.copyOfRange(0, utf8Bom.size).contentEquals(utf8Bom)
        val textBytes = if (hasBom) original.copyOfRange(utf8Bom.size, original.size) else original
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val originalText = decoder.decode(ByteBuffer.wrap(textBytes)).toString()
        val update = updateGdbPort(originalText, port)
        if (update.content == originalText) {
            return DolphinIniApplyResult(changed = false, previousPort = update.previousPort)
        }

        val updatedText = update.content.toByteArray(StandardCharsets.UTF_8)
        val updated = if (hasBom) utf8Bom + updatedText else updatedText
        saveBackup(context, original)
        try {
            writeDocument(context, uri, updated)
        } catch (error: Exception) {
            runCatching { writeDocument(context, uri, original) }
            throw IOException("Could not update Dolphin.ini; the original backup was preserved.", error)
        }
        return DolphinIniApplyResult(changed = true, previousPort = update.previousPort)
    }

    private fun requireDolphinIniName(context: Context, uri: Uri) {
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
        require(displayName.equals("Dolphin.ini", ignoreCase = true)) {
            "Select Config/Dolphin.ini from Dolphin's user data folder."
        }
    }

    private fun writeDocument(context: Context, uri: Uri, bytes: ByteArray) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rwt")
            ?: error("Could not open Dolphin.ini for writing.")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun saveBackup(context: Context, bytes: ByteArray) {
        val directory = File(context.filesDir, "dolphin_ini_backups").apply {
            check(exists() || mkdirs()) { "Could not create the Dolphin.ini backup folder." }
        }
        File(directory, "${System.currentTimeMillis()}-Dolphin.ini").writeBytes(bytes)
        directory.listFiles()
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_BACKUPS)
            .forEach { it.delete() }
    }

    private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = maxBytes
        while (remaining > 0) {
            val count = read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }
}
