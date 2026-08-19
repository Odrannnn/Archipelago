package eu.odran.archipelago

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** App-private caches for user-supplied, validated base ROMs. */
object BaseRomCache {
    private const val CACHE_DIRECTORY = "base_rom"
    private const val MAX_CACHED_ROM_BYTES = 32L * 1024L * 1024L + 0x200

    private val lock = Any()

    fun isPresent(context: Context, game: String): Boolean =
        cacheDirectory(context).listFiles()?.any { it.isFile && it.name.startsWith(cachePrefix(game)) } == true ||
            BaseRomDocumentStore.isPresent(context, game)

    /** Returns cached bytes, removing incomplete or unexpectedly large entries. */
    fun load(context: Context, game: String, inputKey: String): ByteArray? = synchronized(lock) {
        val file = cacheFile(context, game, inputKey)
        if (!file.isFile) return@synchronized null
        if (file.length() <= 0 || file.length() > MAX_CACHED_ROM_BYTES) {
            file.delete()
            return@synchronized null
        }
        runCatching { file.readBytes().also(::requireCacheable) }.getOrElse {
            file.delete()
            null
        }
    }

    /** Cache exact ROM bytes only after the game's patch handler validates and uses them successfully. */
    fun storeAfterSuccessfulPatch(
        context: Context,
        selectedRom: ByteArray,
        game: String,
        inputKey: String,
    ): ByteArray = synchronized(lock) {
        requireCacheable(selectedRom)
        write(context, cacheName(game, inputKey), game, inputKey, selectedRom)
    }

    private fun write(
        context: Context,
        fileName: String,
        game: String,
        inputKey: String,
        bytes: ByteArray,
    ): ByteArray {
        val destination = cacheFile(context, game, inputKey)
        val directory = destination.parentFile ?: error("Could not locate private ROM cache storage.")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create private ROM cache storage." }

        val temporary = File(directory, "$fileName.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (destination.exists() && !destination.delete()) {
                error("Could not replace the cached base ROM.")
            }
            check(temporary.renameTo(destination)) { "Could not finish caching the base ROM." }
        } finally {
            temporary.delete()
        }
        return bytes
    }

    fun forget(context: Context, game: String): Boolean = synchronized(lock) {
        val bytesForgotten = cacheDirectory(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith(cachePrefix(game)) }
            ?.all { it.delete() }
            ?: true
        bytesForgotten && BaseRomDocumentStore.forget(context, game)
    }

    private fun cacheDirectory(context: Context) = File(context.noBackupFilesDir, CACHE_DIRECTORY)

    private fun cacheFile(context: Context, game: String, inputKey: String) =
        File(cacheDirectory(context), cacheName(game, inputKey))

    private fun cachePrefix(game: String) = "rom_${hash(game)}_"

    private fun cacheName(game: String, inputKey: String) = "${cachePrefix(game)}${hash(inputKey)}.bin"

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }

    private fun requireCacheable(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_CACHED_ROM_BYTES) {
            "The selected ROM cannot be cached because its size is invalid."
        }
    }
}
