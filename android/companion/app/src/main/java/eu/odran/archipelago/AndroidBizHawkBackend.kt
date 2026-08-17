package eu.odran.archipelago

import android.util.Base64
import org.json.JSONArray

/**
 * Chaquopy-facing implementation of the standard Archipelago BizHawk memory
 * API. Despite that API name, every request is executed by the custom mGBA
 * core on its emulation thread.
 */
class AndroidBizHawkBackend(
    bridge: MGBABridgeClient,
    private val platform: Int,
) {
    private data class DomainRead(val offset: Long, val length: Int, val domain: String)

    @Volatile private var bridge: MGBABridgeClient? = bridge
    private val system = when (platform) {
        0 -> "GBA"
        1 -> bridge.romRead(0x143, 1).single().let { colorFlag ->
            if ((colorFlag.toInt() and 0xff) in setOf(0x80, 0xc0)) "GBC" else "GB"
        }
        else -> error("Unsupported mGBA platform: $platform")
    }
    fun read(requestsJson: String): String {
        val requests = parseDomainReads(JSONArray(requestsJson))
        val activeBridge = currentBridge()
        val reads = if (requests.all { it.domain != "ROM" }) {
            activeBridge.batchRead(requests.map(::busReadRequest))
        } else {
            requests.map { request ->
                if (request.domain == "ROM") {
                    activeBridge.romRead(request.offset, request.length)
                } else {
                    activeBridge.read(busAddress(request.domain, request.offset), request.length)
                }
            }
        }
        return encodeReads(reads)
    }

    fun guardedRead(requestsJson: String, guardsJson: String): String? {
        val requests = parseDomainReads(JSONArray(requestsJson)).map(::busReadRequest)
        val guards = parseGuards(JSONArray(guardsJson))
        return currentBridge().guardedRead(requests, guards)?.let(::encodeReads)
    }

    fun write(writesJson: String) {
        val writes = parseWrites(JSONArray(writesJson))
        currentBridge().guardedWrites(writes, emptyList())
    }

    fun guardedWrite(writesJson: String, guardsJson: String): Boolean = currentBridge().guardedWrites(
        parseWrites(JSONArray(writesJson)),
        parseGuards(JSONArray(guardsJson)),
    )

    fun readSavedata(offset: Long, length: Int): ByteArray = currentBridge().savedataRead(offset, length)

    fun displayMessage(message: String) = currentBridge().showMessage(message)

    fun getHash(): String = currentBridge().romSha1()

    fun getSystem(): String = system

    fun attach(newBridge: MGBABridgeClient, newPlatform: Int) {
        require(newPlatform == platform) { "mGBA platform changed while reconnecting" }
        bridge = newBridge
    }

    fun detach(oldBridge: MGBABridgeClient) {
        if (bridge === oldBridge) bridge = null
    }

    private fun currentBridge(): MGBABridgeClient =
        checkNotNull(bridge) { "mGBA bridge is temporarily unavailable" }

    fun getMemorySize(domain: String): Int {
        val normalized = normaliseDomain(domain)
        if (system in setOf("GB", "GBC")) {
            return when (normalized) {
                "SYSTEM BUS" -> 0x00010000
                "ROM" -> 0x00800000
                else -> error("Unsupported $system memory domain: $domain")
            }
        }
        return when (normalized) {
            "SYSTEM BUS" -> 0x10000000
            "BIOS" -> 0x00004000
            "EWRAM" -> 0x00040000
            "IWRAM" -> 0x00008000
            "PALRAM" -> 0x00000400
            "VRAM" -> 0x00018000
            "OAM" -> 0x00000400
            "ROM" -> 0x02000000
            "SRAM" -> 0x00010000
            else -> error("Unsupported $system memory domain: $domain")
        }
    }

    private fun parseDomainReads(array: JSONArray): List<DomainRead> =
        List(array.length()) { index ->
            val item = array.getJSONArray(index)
            DomainRead(
                offset = item.getLong(0).also { require(it >= 0) { "Memory offset must be positive" } },
                length = item.getInt(1),
                domain = normaliseDomain(item.getString(2)),
            )
        }

    private fun busReadRequest(request: DomainRead) = MGBABridgeClient.ReadRequest(
        address = busAddress(request.domain, request.offset),
        length = request.length,
    )

    private fun parseGuards(array: JSONArray): List<MGBABridgeClient.MemoryGuard> =
        List(array.length()) { index ->
            val item = array.getJSONArray(index)
            MGBABridgeClient.MemoryGuard(
                address = busAddress(item.getString(2), item.getLong(0)),
                expected = decodeBytes(item.getString(1)),
            )
        }

    private fun parseWrites(array: JSONArray): List<MGBABridgeClient.WriteRequest> =
        List(array.length()) { index ->
            val item = array.getJSONArray(index)
            MGBABridgeClient.WriteRequest(
                address = busAddress(item.getString(2), item.getLong(0)),
                value = decodeBytes(item.getString(1)),
            )
        }

    private fun busAddress(domain: String, offset: Long): Long {
        require(offset >= 0) { "Memory offset must be positive" }
        if (system in setOf("GB", "GBC")) {
            require(normaliseDomain(domain) == "SYSTEM BUS") {
                "Unsupported $system bus memory domain: $domain"
            }
            require(offset <= 0xffff) { "GB system-bus offset is out of range" }
            return offset
        }
        val base = when (normaliseDomain(domain)) {
            "SYSTEM BUS" -> 0x00000000L
            "BIOS" -> 0x00000000L
            "EWRAM" -> 0x02000000L
            "IWRAM" -> 0x03000000L
            "PALRAM" -> 0x05000000L
            "VRAM" -> 0x06000000L
            "OAM" -> 0x07000000L
            "ROM" -> 0x08000000L
            "SRAM" -> 0x0E000000L
            else -> error("Unsupported GBA memory domain: $domain")
        }
        return base + offset
    }

    private fun normaliseDomain(domain: String): String = domain
        .replace(" ", "")
        .replace("_", "")
        .uppercase()
        .let { compact -> if (compact == "SYSTEMBUS") "SYSTEM BUS" else compact }

    private fun decodeBytes(encoded: String): ByteArray = Base64.decode(encoded, Base64.NO_WRAP)

    private fun encodeReads(reads: List<ByteArray>): String = JSONArray().apply {
        reads.forEach { put(Base64.encodeToString(it, Base64.NO_WRAP)) }
    }.toString()
}
