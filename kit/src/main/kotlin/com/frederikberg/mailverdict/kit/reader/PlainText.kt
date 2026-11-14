package com.frederikberg.mailverdict.kit.reader

/** A web address inside plain text, by character range. */
data class LinkSpan(
    val start: Int,
    val end: Int,
    val url: String,
)

/** A plain-text body prepared for drawing: bounded, with its links found before layout. */
data class PlainBody(
    val text: String,
    val links: List<LinkSpan>,
    /** The body was longer than [PlainText.MAX_CHARS] and this is its start. */
    val truncated: Boolean,
)

object PlainText {
    /** Past this a body is cut for drawing; the whole of it is one tap away, so a megabyte of log output never has to be laid out to be scrolled past. */
    const val MAX_CHARS = 30_000

    private val url = Regex("""https?://[^\s<>"']+""")
    private const val TRAILING = ".,;:!?)]}'\""

    fun prepare(
        raw: String,
        limit: Int = MAX_CHARS,
    ): PlainBody {
        val truncated = raw.length > limit
        val text = if (truncated) raw.take(limit) else raw
        return PlainBody(text, links(text), truncated)
    }

    /** Links stop before trailing punctuation, which belongs to the sentence. */
    fun links(text: String): List<LinkSpan> =
        url
            .findAll(text)
            .map { match ->
                var end = match.range.last + 1
                while (end > match.range.first + 1 && text[end - 1] in TRAILING) end--
                LinkSpan(match.range.first, end, text.substring(match.range.first, end))
            }.toList()
}
