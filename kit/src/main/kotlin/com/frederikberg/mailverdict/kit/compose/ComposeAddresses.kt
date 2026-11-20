package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.kit.format.MailFormat

/** Address handling of the composer: the web's `lib/format.ts` and `lib/reply.ts` helpers. */
object ComposeAddresses {
    private val validEmail = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

    /**
     * One address per entry of a header that may list several. A comma or semicolon inside a quoted
     * display name (`"Doe, Jane" <jane@x.test>`) or inside angle brackets separates nothing.
     */
    fun splitHeader(header: String?): List<String> {
        if (header == null) return emptyList()
        val entries = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var inAngle = false
        for (char in header) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == '<' && !inQuotes -> inAngle = true
                char == '>' && !inQuotes -> inAngle = false
                (char == ',' || char == ';') && !inQuotes && !inAngle -> {
                    entries += current.toString()
                    current.clear()
                    continue
                }
            }
            current.append(char)
        }
        entries += current.toString()
        return entries.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Case-insensitive, first occurrence wins, empty entries and [exclude] (lower case) dropped. */
    fun dedupe(
        addresses: List<String>,
        exclude: Set<String> = emptySet(),
    ): List<String> {
        val seen = HashSet<String>()
        return addresses.filter { address ->
            val key = address.lowercase()
            address.isNotEmpty() && key !in exclude && seen.add(key)
        }
    }

    /** A comma- or semicolon-separated field as its entries, unvalidated. */
    fun parseList(value: String): List<String> = value.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /** A shape check (one `@`, something on each side, a dot after it, no space), not RFC 5322. */
    fun isValid(address: String): Boolean = validEmail.matches(address)

    /** `Name <address>` becomes the address; anything else is only trimmed. */
    fun normalize(entry: String): String {
        val trimmed = entry.trim()
        if ('<' !in trimmed || '>' !in trimmed) return trimmed
        return MailFormat.email(trimmed).trim()
    }

    fun email(address: String?): String = MailFormat.email(address)
}
