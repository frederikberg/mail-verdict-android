package com.frederikberg.mailverdict.kit.search

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/** A hit prepared for one list row, so the composable only draws. */
data class SearchRowData(
    val id: String,
    val sender: String,
    val date: String,
    val subject: String,
    /** The snippet in order, with the matched words marked; their text carries no markers. */
    val snippet: List<SnippetRun>,
    val isUnread: Boolean,
    val isFlagged: Boolean,
    val hasAttachments: Boolean,
    val isSpam: Boolean,
    val threadCount: Int?,
)

/** A stretch of a snippet; [matched] marks the words the query found. */
data class SnippetRun(
    val text: String,
    val matched: Boolean,
)

object SearchRowFormat {
    fun row(
        hit: SearchHit,
        now: Instant,
        zone: ZoneId,
        locale: Locale,
    ) = SearchRowData(
        id = hit.id,
        sender = senderLabel(hit.fromAddr),
        date = dateLabel(hit.receivedAt, now, zone, locale),
        subject =
            hit.subject
                ?.trim()
                .orEmpty()
                .ifEmpty { NO_SUBJECT },
        snippet = snippetRuns(hit.snippet),
        isUnread = !hit.isSeen,
        isFlagged = hit.isFlagged,
        hasAttachments = hit.hasAttachments,
        isSpam = hit.verdictIsSpam == true,
        threadCount = hit.threadCount?.takeIf { it > 1 },
    )

    /**
     * The server marks matched words in a snippet as `**word**`. Anything else, including a lone `**`,
     * stays as text.
     */
    fun snippetRuns(snippet: String?): List<SnippetRun> {
        val text = snippet?.replace(WHITESPACE, " ")?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val runs = ArrayList<SnippetRun>()
        var rest = text
        while (rest.isNotEmpty()) {
            val open = rest.indexOf(MARK)
            val close = if (open >= 0) rest.indexOf(MARK, open + MARK.length) else -1
            if (open < 0 || close < 0) {
                runs += SnippetRun(rest, false)
                break
            }
            if (open > 0) runs += SnippetRun(rest.substring(0, open), false)
            runs += SnippetRun(rest.substring(open + MARK.length, close), true)
            rest = rest.substring(close + MARK.length)
        }
        return runs.filter { it.text.isNotEmpty() }
    }

    /** `"Ada Lovelace" <ada@example.com>` shows as the name; a bare address stays as it is. */
    fun senderLabel(from: String?): String {
        val raw = from?.trim().orEmpty()
        if (raw.isEmpty()) return UNKNOWN_SENDER
        val name =
            raw
                .substringBefore('<')
                .trim()
                .trim('"')
                .trim()
        return name.ifEmpty { raw.removePrefix("<").removeSuffix(">") }
    }

    /** The time today, the day and month this year, the full date before that, in the device's zone. */
    fun dateLabel(
        receivedAt: String?,
        now: Instant,
        zone: ZoneId,
        locale: Locale,
    ): String {
        val instant =
            try {
                OffsetDateTime.parse(receivedAt ?: return "").toInstant()
            } catch (_: DateTimeParseException) {
                return ""
            }
        val day = instant.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val formatter =
            when {
                day == today -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                day.year == today.year -> DateTimeFormatter.ofPattern("d MMM")
                else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            }
        return formatter.withLocale(locale).withZone(zone).format(instant)
    }

    private const val MARK = "**"
    private val WHITESPACE = Regex("\\s+")
    private const val NO_SUBJECT = "(no subject)"
    private const val UNKNOWN_SENDER = "Unknown sender"
}
