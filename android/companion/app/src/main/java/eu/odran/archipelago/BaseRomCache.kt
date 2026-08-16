package eu.odran.archipelago

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** App-private caches for user-supplied, validated base ROMs. */
object BaseRomCache {
    private const val CACHE_DIRECTORY = "base_rom"
    private const val COPIER_HEADER_SIZE = 0x200
    private const val COPIER_HEADER_REMAINDER = 0x200
    private const val ROM_SIZE_BLOCK = 0x400
    private const val MAX_CACHED_ROM_BYTES = 32L * 1024L * 1024L + COPIER_HEADER_SIZE

    private val lock = Any()
    private data class RomSpec(
        val cacheFile: String,
        val displayName: String,
        val acceptedMd5s: Set<String>,
    )

    private val specs = mapOf(
        "Metroid Fusion" to RomSpec(
            "metroid_fusion_usa.gba",
            "an unmodified North American Metroid Fusion ROM",
            setOf(
                "af5040fc0f579800151ee2a683e2e5b5",
                "5d07cc8a45eae858bea6dfc97f63e813",
                "27d02a4f03e172e029c9b82ac3db79f7",
            ),
        ),
        "The Minish Cap" to RomSpec(
            "the_minish_cap_europe.gba",
            "an unmodified European The Legend of Zelda: The Minish Cap ROM",
            setOf("2af78edbe244b5de44471368ae2b6f0b"),
        ),
    )

    fun isPresent(context: Context, game: String = "Metroid Fusion"): Boolean = cacheFile(context, game).isFile

    /** Returns a validated cached ROM, removing the cache if it has become invalid. */
    fun load(context: Context, game: String = "Metroid Fusion"): ByteArray? = synchronized(lock) {
        val file = cacheFile(context, game)
        if (!file.isFile) return@synchronized null
        if (file.length() <= 0 || file.length() > MAX_CACHED_ROM_BYTES) {
            file.delete()
            return@synchronized null
        }
        runCatching { validateAndNormalize(game, file.readBytes()) }
            .getOrElse {
                file.delete()
                null
            }
    }

    /** Validates and stores a headerless copy, returning the bytes ready for patching. */
    fun store(context: Context, selectedRom: ByteArray, game: String = "Metroid Fusion"): ByteArray = synchronized(lock) {
        val spec = spec(game)
        val normalized = validateAndNormalize(game, selectedRom)
        val destination = cacheFile(context, game)
        val directory = destination.parentFile ?: error("Could not locate private ROM cache storage.")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create private ROM cache storage." }

        val temporary = File(directory, "${spec.cacheFile}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(normalized)
                output.fd.sync()
            }
            if (destination.exists() && !destination.delete()) {
                error("Could not replace the cached base ROM.")
            }
            check(temporary.renameTo(destination)) { "Could not finish caching the base ROM." }
        } finally {
            temporary.delete()
        }
        normalized
    }

    fun forget(context: Context, game: String = "Metroid Fusion"): Boolean = synchronized(lock) {
        val file = cacheFile(context, game)
        !file.exists() || file.delete()
    }

    private fun cacheFile(context: Context, game: String) = File(
        context.noBackupFilesDir,
        "$CACHE_DIRECTORY/${spec(game).cacheFile}",
    )

    private fun validateAndNormalize(game: String, rawRom: ByteArray): ByteArray {
        val spec = spec(game)
        require(rawRom.isNotEmpty() && rawRom.size.toLong() <= MAX_CACHED_ROM_BYTES) {
            "Wrong base ROM. Select ${spec.displayName}."
        }
        val normalized = if (rawRom.size % ROM_SIZE_BLOCK == COPIER_HEADER_REMAINDER) {
            rawRom.copyOfRange(COPIER_HEADER_SIZE, rawRom.size)
        } else {
            rawRom
        }
        val rawMd5 = rawRom.md5()
        val normalizedMd5 = normalized.md5()
        require(rawMd5 in spec.acceptedMd5s || normalizedMd5 in spec.acceptedMd5s) {
            "Wrong base ROM. Select ${spec.displayName}. Its MD5 is ${rawMd5.uppercase()}."
        }
        return normalized
    }

    private fun spec(game: String): RomSpec = specs[game] ?: error("Unsupported base ROM game: $game")

    private fun ByteArray.md5(): String = MessageDigest.getInstance("MD5")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
