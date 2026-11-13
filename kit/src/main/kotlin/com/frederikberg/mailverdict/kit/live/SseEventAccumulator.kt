package com.frederikberg.mailverdict.kit.live

/** One complete `text/event-stream` record. */
data class SseRecord(
    /**
     * The value a reconnect sends back as `Last-Event-ID`. The server prefixes every id with its own
     * epoch so a client reconnecting after a restart can tell "mine, replay from here" from "another
     * process's id, resync instead"; the client carries the string opaquely.
     */
    val id: String?,
    val name: String,
    val data: String,
)

/**
 * Accumulates `id:` / `event:` / `data:` lines into records, framed by a blank line. Free of any
 * transport so the framing rules are testable on their own.
 */
class SseEventAccumulator {
    private var id: String? = null
    private var name: String? = null
    private val data = mutableListOf<String>()

    /**
     * Feeds one line. Returns the completed record the moment a blank line terminates it, and null
     * while one is still accumulating; a blank line closing a record with no event name or no data
     * discards it.
     */
    fun ingest(line: String): SseRecord? {
        if (line.isEmpty()) {
            val record = name?.takeIf { data.isNotEmpty() }?.let { SseRecord(id, it, data.joinToString("\n")) }
            id = null
            name = null
            data.clear()
            return record
        }
        // A comment line carries no event: the opening padding comment, or a keep-alive.
        if (line.startsWith(":")) return null
        when {
            line.startsWith("id:") -> id = line.removePrefix("id:").trim()
            line.startsWith("event:") -> name = line.removePrefix("event:").trim()
            line.startsWith("data:") -> data += dataValue(line.removePrefix("data:"))
        }
        // Any other field (retry:, ...) goes unused.
        return null
    }

    companion object {
        /** Per the SSE spec at most one leading space after `data:` is framing; the rest of the line is payload. */
        fun dataValue(
            afterColon: String,
        ): String = if (afterColon.startsWith(" ")) afterColon.substring(1) else afterColon
    }
}
