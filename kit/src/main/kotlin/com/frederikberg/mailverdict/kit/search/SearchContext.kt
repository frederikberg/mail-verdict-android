package com.frederikberg.mailverdict.kit.search

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@Serializable
enum class SearchMode { Text, Semantic }

/** A message field a text search can be narrowed to; [wire] is the value the server's `fields` parameter takes. */
@Serializable
enum class SearchField(
    val wire: String,
) {
    Subject("subject"),
    From("from"),
    To("to"),
    Body("body"),
}

/**
 * How far back a search looks. A preset is kept as the preset and resolved against the clock each
 * time a query runs, so "Last 7 days" keeps rolling.
 */
@Serializable
enum class TimeWindow {
    Today,
    SinceYesterday,
    Last7Days,
    Last30Days,
    ;

    /** The `received_after` / `received_before` bounds at [now]; the end is always [now]. */
    fun bounds(
        now: Instant,
        zone: ZoneId,
    ): Pair<Instant, Instant> {
        val startOfToday =
            ZonedDateTime
                .ofInstant(now, zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toInstant()
        val since =
            when (this) {
                Today -> startOfToday
                SinceYesterday ->
                    ZonedDateTime.ofInstant(startOfToday, zone).minusDays(1).toInstant()
                Last7Days -> now.minusSeconds(7 * SECONDS_PER_DAY)
                Last30Days -> now.minusSeconds(30 * SECONDS_PER_DAY)
            }
        return since to now
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}

/**
 * Everything that defines which list a search is: changing any of it is a new list. [folderIds] null
 * means every folder and an empty list means search nothing, which is a different thing and is never
 * sent.
 */
@Serializable
data class SearchContext(
    val mode: SearchMode = SearchMode.Text,
    val query: String = "",
    val accountId: String? = null,
    val folderIds: List<String>? = null,
    val fields: List<SearchField>? = null,
    val timeWindow: TimeWindow? = null,
) {
    /** The text query that is shown ahead of a semantic one: every field, same scope and window. */
    val fullTextCounterpart: SearchContext get() = copy(mode = SearchMode.Text, fields = null)

    /** A query shorter than this is not sent: one letter matches too much to be a search. */
    val isSearchable: Boolean get() = query.trim().length >= MIN_QUERY_LENGTH && folderIds?.isEmpty() != true

    companion object {
        const val MIN_QUERY_LENGTH = 2
    }
}
