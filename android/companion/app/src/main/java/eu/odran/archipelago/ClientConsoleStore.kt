package eu.odran.archipelago

import java.util.concurrent.ConcurrentLinkedQueue

/** Process-local, bounded transcript and command inbox shared with BridgeService. */
object ClientConsoleStore {
    data class Entry(
        val id: Long,
        val timestamp: Long,
        val kind: String,
        val text: String,
    )

    data class Snapshot(val revision: Long, val entries: List<Entry>)

    private const val MAX_ENTRIES = 500
    private val entries = ArrayDeque<Entry>()
    private val pendingCommands = ConcurrentLinkedQueue<String>()
    private var nextId = 1L
    private var revision = 0L

    @Synchronized
    fun append(kind: String, text: String) {
        entries.addLast(Entry(nextId++, System.currentTimeMillis(), kind, text))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        revision++
    }

    fun append(messages: List<ClientConsoleMessage>) =
        messages.forEach { append(it.kind, it.text) }

    fun submit(raw: String): Boolean {
        val command = raw.trim()
        if (command.isEmpty()) return false
        append("input", command)
        pendingCommands.add(command)
        return true
    }

    fun pollCommand(): String? = pendingCommands.poll()

    @Synchronized
    fun clear() {
        entries.clear()
        revision++
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(revision, entries.toList())
}
