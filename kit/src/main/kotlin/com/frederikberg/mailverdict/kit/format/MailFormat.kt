package com.frederikberg.mailverdict.kit.format

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Day-first, 24-hour, English month and weekday names whatever the device's locale: the one date and
 * address convention every mail screen uses, the same as the web UI and the iPhone app.
 */
object MailFormat {
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val full = DateTimeFormatter.ofPattern("EEE, d MMM yyyy, HH:mm", Locale.ENGLISH)

    /** Instants the server wrote, with or without an offset; null for anything unreadable. */
    fun parseInstant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(iso).toInstant() }
            .recoverCatching { Instant.parse(iso) }
            .getOrNull()
    }

    /**
     * A list row's timestamp: the time for today, "Yesterday", the weekday for the six days before,
     * then a day-first date, with the year only when it is not the current one. Never a relative
     * duration, which drifts while the row stays as it is. A date in the future is always the plain
     * date, never a bare weekday that would pass for one in the past week.
     */
    fun listDate(
        iso: String?,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val instant = parseInstant(iso) ?: return ""
        val date = instant.atZone(zone)
        val daysAgo = ChronoUnit.DAYS.between(date.toLocalDate(), now.atZone(zone).toLocalDate())
        return when {
            daysAgo == 0L -> time.format(date)
            daysAgo == 1L -> "Yesterday"
            daysAgo in 2..6 -> weekday.format(date)
            else ->
                sameYear(
                    date,
                    now.atZone(zone),
                ).let { if (it) dayMonth.format(date) else dayMonthYear.format(date) }
        }
    }

    /** The full date and time shown in a message header. */
    fun fullDate(
        iso: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = parseInstant(iso)?.atZone(zone)?.let(full::format).orEmpty()

    private fun sameYear(
        a: ZonedDateTime,
        b: ZonedDateTime,
    ) = a.year == b.year

    private val senderName = Regex("""^"?([^"<]+)"?\s*<.*>$""")

    /** The display name of `"Name <a@b>"`, or the local part of a bare address; "Unknown" with nothing. */
    fun senderName(from: String?): String {
        if (from.isNullOrEmpty()) return "Unknown"
        senderName.find(from)?.let { return it.groupValues[1].trim() }
        return from.substringBefore('@')
    }

    /** The bare address of `"Name <a@b>"`; anything without angle brackets comes back as it is. */
    fun email(from: String?): String {
        if (from == null) return ""
        val open = from.indexOf('<')
        val close = from.indexOf('>')
        return if (open in 0 until close) from.substring(open + 1, close) else from
    }

    /** "1.5 KB". */
    fun size(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB")
        var exponent = 0
        var value = bytes.toDouble()
        while (value >= 1024 && exponent < units.lastIndex) {
            value /= 1024
            exponent++
        }
        return if (exponent == 0) "$bytes B" else String.format(Locale.ENGLISH, "%.1f %s", value, units[exponent])
    }
}
