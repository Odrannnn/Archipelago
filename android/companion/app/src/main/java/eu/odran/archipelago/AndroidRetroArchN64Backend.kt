package eu.odran.archipelago

import android.content.Context
import android.util.Base64
import org.json.JSONArray

internal interface RetroArchCoreMemoryAccess {
    fun read(address: Long, length: Int): ByteArray
    fun write(address: Long, data: ByteArray)
}

private class NetworkCommandMemoryAccess(
    private var client: RetroArchNetworkClient?,
) : RetroArchCoreMemoryAccess {
    override fun read(address: Long, length: Int): ByteArray = current().readCoreMemory(address, length)

    override fun write(address: Long, data: ByteArray) = current().writeCoreMemory(address, data)

    fun attach(newClient: RetroArchNetworkClient) {
        client = newClient
    }

    fun detach(oldClient: RetroArchNetworkClient) {
        if (client === oldClient) client = null
    }

    private fun current(): RetroArchNetworkClient =
        checkNotNull(client) { "RetroArch N64 memory is temporarily unavailable" }
}

/**
 * Standard Archipelago BizHawk memory surface backed by RetroArch Network Commands.
 *
 * Mupen64Plus-Next publishes RDRAM at KSEG1 0xa0000000 and cartridge ROM at
 * 0xb0000000. On little-endian Android, both regions may be exposed with bytes
 * reversed inside each native 32-bit word, so the mapping is detected from the
 * canonical N64 ROM header and applied to every domain operation.
 */
class AndroidRetroArchN64Backend internal constructor(
    private val access: RetroArchCoreMemoryAccess,
) {
    private data class DomainRange(val offset: Long, val length: Int, val domain: String)
    private data class DomainValue(val offset: Long, val value: ByteArray, val domain: String)

    private var ootConnector: AndroidOotLuaConnector? = null

    constructor(client: RetroArchNetworkClient, context: Context) : this(NetworkCommandMemoryAccess(client)) {
        ootConnector = AndroidOotLuaConnector(context, this)
    }

    fun attach(client: RetroArchNetworkClient) {
        (access as? NetworkCommandMemoryAccess)?.attach(client)
            ?: error("This N64 backend does not support transport replacement")
        byteXorMask = null
        snapshotPages.clear()
        ootConnector?.reset()
    }

    fun detach(client: RetroArchNetworkClient) {
        (access as? NetworkCommandMemoryAccess)?.detach(client)
        snapshotPages.clear()
    }

    private var byteXorMask: Int? = null
    private var snapshotDepth = 0
    private val snapshotPages = linkedMapOf<Long, ByteArray>()

    fun read(requestsJson: String): String = encodeReads(
        parseRanges(JSONArray(requestsJson)).map { request ->
            readDomain(request.domain, request.offset, request.length)
        },
    )

    fun guardedRead(requestsJson: String, guardsJson: String): String? {
        val guards = parseValues(JSONArray(guardsJson))
        if (!guardsMatch(guards)) return null
        return read(requestsJson)
    }

    fun write(writesJson: String) {
        parseValues(JSONArray(writesJson)).forEach { writeDomain(it.domain, it.offset, it.value) }
    }

    fun guardedWrite(writesJson: String, guardsJson: String): Boolean {
        val guards = parseValues(JSONArray(guardsJson))
        if (!guardsMatch(guards) || !guardsMatch(guards)) return false
        parseValues(JSONArray(writesJson)).forEach { writeDomain(it.domain, it.offset, it.value) }
        return true
    }

    fun readSavedata(@Suppress("UNUSED_PARAMETER") offset: Long, @Suppress("UNUSED_PARAMETER") length: Int): ByteArray =
        error("The generic N64 backend does not expose cartridge save storage")

    fun displayMessage(@Suppress("UNUSED_PARAMETER") message: String) = Unit

    fun getHash(): String = error(
        "RetroArch Network Commands does not expose the loaded ROM length required for a reliable full-ROM hash",
    )

    fun getSystem(): String = "N64"

    fun getMemorySize(domain: String): Int = when (normaliseDomain(domain)) {
        "RDRAM", "SYSTEM BUS" -> RDRAM_SIZE
        "ROM" -> MAX_ROM_SIZE
        else -> error("Unsupported N64 memory domain: $domain")
    }

    fun beginSnapshot() {
        snapshotDepth++
        if (snapshotDepth == 1) snapshotPages.clear()
    }

    fun endSnapshot() {
        if (snapshotDepth > 0) snapshotDepth--
        if (snapshotDepth == 0) snapshotPages.clear()
    }

    fun readRdram(offset: Long, length: Int): ByteArray = readDomain("RDRAM", offset, length)

    fun writeRdram(offset: Long, value: ByteArray) = writeDomain("RDRAM", offset, value)

    fun readRom(offset: Long, length: Int): ByteArray = readDomain("ROM", offset, length)

    fun isOotRom(): Boolean = checkNotNull(ootConnector) {
        "The OoT connector was not configured for this N64 backend"
    }.isOotRom()

    fun ootIdentity(): String = checkNotNull(ootConnector) {
        "The OoT connector was not configured for this N64 backend"
    }.identity()

    fun ootExchange(payloadJson: String): String = checkNotNull(ootConnector) {
        "The OoT connector was not configured for this N64 backend"
    }.exchange(payloadJson)

    private fun guardsMatch(guards: List<DomainValue>): Boolean = guards.all { guard ->
        readDomain(guard.domain, guard.offset, guard.value.size).contentEquals(guard.value)
    }

    private fun readDomain(domain: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "N64 memory offset must be positive" }
        require(length >= 0) { "N64 memory length must be positive" }
        if (length == 0) return byteArrayOf()
        val normalized = normaliseDomain(domain)
        val size = getMemorySize(normalized).toLong()
        require(offset <= size && length.toLong() <= size - offset) {
            "$normalized read is outside its memory domain"
        }
        val base = domainBase(normalized)
        val mask = detectByteXorMask()
        val alignedStart = offset and -4L
        val alignedEnd = (offset + length + 3L) and -4L
        val raw = readPhysical(base, alignedStart, (alignedEnd - alignedStart).toInt())
        return ByteArray(length) { index ->
            val logical = offset + index
            val physical = (logical xor mask.toLong()) - alignedStart
            raw[physical.toInt()]
        }
    }

    private fun writeDomain(domain: String, offset: Long, value: ByteArray) {
        require(value.isNotEmpty()) { "N64 writes may not be empty" }
        val normalized = normaliseDomain(domain)
        require(normalized != "ROM") { "N64 cartridge ROM is read-only" }
        val size = getMemorySize(normalized).toLong()
        require(offset >= 0 && offset <= size && value.size.toLong() <= size - offset) {
            "$normalized write is outside its memory domain"
        }
        val base = domainBase(normalized)
        val mask = detectByteXorMask()
        val bytesByPhysicalOffset = value.indices
            .map { index -> ((offset + index) xor mask.toLong()) to value[index] }
            .sortedBy { it.first }
        var index = 0
        while (index < bytesByPhysicalOffset.size) {
            val start = bytesByPhysicalOffset[index].first
            var end = index + 1
            while (
                end < bytesByPhysicalOffset.size &&
                bytesByPhysicalOffset[end].first == bytesByPhysicalOffset[end - 1].first + 1
            ) {
                end++
            }
            access.write(
                base + start,
                ByteArray(end - index) { bytesByPhysicalOffset[index + it].second },
            )
            index = end
        }
        snapshotPages.clear()
    }

    private fun readPhysical(base: Long, offset: Long, length: Int): ByteArray {
        if (snapshotDepth <= 0 || base != RDRAM_BASE) return readPhysicalUncached(base + offset, length)
        val result = ByteArray(length)
        var copied = 0
        while (copied < length) {
            val absoluteOffset = offset + copied
            val pageOffset = absoluteOffset / SNAPSHOT_PAGE_SIZE * SNAPSHOT_PAGE_SIZE
            val page = snapshotPages.getOrPut(pageOffset) {
                readPhysicalUncached(base + pageOffset, SNAPSHOT_PAGE_SIZE)
            }
            val withinPage = (absoluteOffset - pageOffset).toInt()
            val count = minOf(length - copied, page.size - withinPage)
            page.copyInto(result, copied, withinPage, withinPage + count)
            copied += count
        }
        return result
    }

    private fun readPhysicalUncached(address: Long, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = minOf(MAX_NETWORK_READ, length - offset)
            access.read(address + offset, count).copyInto(result, offset)
            offset += count
        }
        return result
    }

    private fun detectByteXorMask(): Int {
        byteXorMask?.let { return it }
        val raw = access.read(ROM_BASE, 4)
        val mask = (0..3).firstOrNull { candidate ->
            ByteArray(4) { raw[it xor candidate] }.contentEquals(N64_HEADER)
        } ?: error(
            "The active Mupen64Plus-Next content does not expose a canonical N64 ROM header " +
                "through RetroArch Network Commands",
        )
        byteXorMask = mask
        return mask
    }

    private fun domainBase(domain: String): Long = when (domain) {
        "RDRAM", "SYSTEM BUS" -> RDRAM_BASE
        "ROM" -> ROM_BASE
        else -> error("Unsupported N64 memory domain: $domain")
    }

    private fun parseRanges(array: JSONArray): List<DomainRange> = List(array.length()) { index ->
        val item = array.getJSONArray(index)
        DomainRange(item.getLong(0), item.getInt(1), item.getString(2))
    }

    private fun parseValues(array: JSONArray): List<DomainValue> = List(array.length()) { index ->
        val item = array.getJSONArray(index)
        DomainValue(
            item.getLong(0),
            Base64.decode(item.getString(1), Base64.NO_WRAP),
            item.getString(2),
        )
    }

    private fun normaliseDomain(domain: String): String = domain
        .replace(" ", "")
        .replace("_", "")
        .uppercase()
        .let { compact -> if (compact == "SYSTEMBUS") "SYSTEM BUS" else compact }

    private fun encodeReads(reads: List<ByteArray>): String = JSONArray().apply {
        reads.forEach { put(Base64.encodeToString(it, Base64.NO_WRAP)) }
    }.toString()

    companion object {
        internal const val RDRAM_BASE = 0xa000_0000L
        internal const val ROM_BASE = 0xb000_0000L
        private const val RDRAM_SIZE = 8 * 1024 * 1024
        private const val MAX_ROM_SIZE = 64 * 1024 * 1024
        private const val MAX_NETWORK_READ = 1_024
        private const val SNAPSHOT_PAGE_SIZE = 512
        private val N64_HEADER = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40)
    }
}
