package com.frederikberg.mailverdict.kit.maillist

import com.frederikberg.mailverdict.contract.MessageSummary
import com.frederikberg.mailverdict.kit.format.MailFormat

/**
 * Refreshing the rows a list already holds in one request. A list is a window of rows; after any
 * change the whole window is re-read with a single request sized to it and the fresh rows are
 * spliced over the loaded ones, so a list twenty pages deep costs one request per change.
 */
object MailListWindow {
    const val PAGE_SIZE = 50

    /** Rows past the loaded window a refresh reads, so mail arriving at the top does not push the window's own last rows out of the response. */
    private const val REFRESH_SLACK = 50

    /** The list endpoints' own `limit` ceiling; rows loaded deeper than this are kept as they are rather than re-read. */
    private const val REFRESH_MAX_ROWS = 1000

    fun refreshLimit(loadedRows: Int): Int = minOf(maxOf(loadedRows, 0) + REFRESH_SLACK, REFRESH_MAX_ROWS)

    data class Merged(
        val rows: List<MessageSummary>,
        val hasMore: Boolean,
    )

    data class MergedFromBelow(
        val rows: List<MessageSummary>,
        val hasNewer: Boolean,
    )

    /**
     * Whether [a] sits above [b] in a newest-first list: the server's own `ORDER BY received_at DESC,
     * id DESC`, where PostgreSQL places a NULL `received_at` first. Lower-case hex ids order the same
     * as the database's byte order.
     */
    fun sitsAbove(
        a: MessageSummary,
        b: MessageSummary,
    ): Boolean {
        val aTime = MailFormat.parseInstant(a.receivedAt)
        val bTime = MailFormat.parseInstant(b.receivedAt)
        if (aTime != bTime) {
            if (aTime == null) return true
            if (bTime == null) return false
            if (aTime != bTime) return aTime > bTime
        }
        return a.id.lowercase() > b.id.lowercase()
    }

    /**
     * Splices a freshly read head window over the loaded rows of a window that starts at the newest
     * message. The result covers the stretch the reader had loaded and no more, so a refresh never
     * grows the list underneath the reader. [preserveIds] are rows a fresh read legitimately dropped
     * but that must stay visible (an unread-only window's rows the reader has since read); ignored for
     * any row [fresh] covers, which is authoritative.
     */
    fun mergeRefreshed(
        current: List<MessageSummary>,
        fresh: List<MessageSummary>,
        freshHasMore: Boolean,
        currentHasMore: Boolean,
        preserveIds: Set<String> = emptySet(),
    ): Merged {
        val oldLast = current.lastOrNull() ?: return Merged(fresh, freshHasMore)
        val effectiveFresh = withPreserved(fresh, current, preserveIds)

        val reachedOldLast = !freshHasMore || effectiveFresh.lastOrNull()?.let { !sitsAbove(it, oldLast) } == true
        if (reachedOldLast) {
            val rows = effectiveFresh.filter { it.id == oldLast.id || sitsAbove(it, oldLast) }
            return Merged(rows, freshHasMore || rows.size < effectiveFresh.size)
        }

        // The fresh read stopped above the old last row: what it covers is authoritative and
        // everything below where it stopped is kept as it was.
        val freshLast = effectiveFresh.lastOrNull() ?: return Merged(current, currentHasMore)
        val freshIds = effectiveFresh.mapTo(HashSet()) { it.id }
        val kept = current.filter { it.id !in freshIds && sitsAbove(freshLast, it) }
        return Merged(effectiveFresh + kept, currentHasMore)
    }

    /**
     * Replaces a window whose rows came from a saved copy with the first fresh read of its newest
     * edge. Nothing below where the read stopped is kept: saved rows are of unknown age, so keeping
     * them would show rows deleted or moved since as live, under a gap hiding every message that
     * arrived between the two.
     */
    fun replacingSavedCopy(
        current: List<MessageSummary>,
        fresh: List<MessageSummary>,
        freshHasMore: Boolean,
        preserveIds: Set<String> = emptySet(),
    ): Merged {
        val effectiveFresh = withPreserved(fresh, current, preserveIds)
        val freshLast = fresh.lastOrNull()
        if (!freshHasMore || freshLast == null) return Merged(effectiveFresh, freshHasMore)
        return Merged(effectiveFresh.filter { it.id == freshLast.id || sitsAbove(it, freshLast) }, true)
    }

    /**
     * The same splice for a window that does not start at the newest message (opened around a
     * message). It is re-read upward from its own last row, and whatever the read finds above the
     * window's first row is dropped rather than added: arrivals never enter a window with a gap above
     * it, they only keep `hasNewer` true for paging to close. The last row is the read's own cursor,
     * so it is kept.
     */
    fun mergeRefreshedFromBelow(
        current: List<MessageSummary>,
        freshAboveLast: List<MessageSummary>,
        freshHasMoreNewer: Boolean,
        preserveIds: Set<String> = emptySet(),
    ): MergedFromBelow {
        val oldFirst = current.firstOrNull()
        val oldLast = current.lastOrNull()
        if (oldFirst == null || oldLast == null) return MergedFromBelow(freshAboveLast, freshHasMoreNewer)
        val body = current.dropLast(1)
        val effectiveFresh = withPreserved(freshAboveLast, body, preserveIds)

        val reachedOldFirst =
            !freshHasMoreNewer || effectiveFresh.firstOrNull()?.let { !sitsAbove(oldFirst, it) } == true
        if (reachedOldFirst) {
            val inside = effectiveFresh.filter { it.id == oldFirst.id || !sitsAbove(it, oldFirst) }
            val droppedAbove = inside.size < effectiveFresh.size
            return MergedFromBelow(inside.filter { it.id != oldLast.id } + oldLast, freshHasMoreNewer || droppedAbove)
        }

        // The read stopped below the old first row: rows above where it stopped are kept as they were.
        val freshFirst = effectiveFresh.firstOrNull() ?: return MergedFromBelow(current, true)
        val freshIds = effectiveFresh.mapTo(HashSet()) { it.id }
        val kept = body.filter { it.id !in freshIds && sitsAbove(it, freshFirst) }
        return MergedFromBelow(kept + effectiveFresh.filter { it.id != oldLast.id } + oldLast, true)
    }

    /**
     * Appends an older page below the loaded rows: only rows that genuinely sit below the current
     * last row, so a page fetched against a cursor a refresh has since moved can never duplicate or
     * reorder anything.
     */
    fun appendingOlder(
        page: List<MessageSummary>,
        rows: List<MessageSummary>,
    ): List<MessageSummary> {
        val last = rows.lastOrNull() ?: return page
        val ids = rows.mapTo(HashSet()) { it.id }
        return rows + page.filter { it.id !in ids && sitsAbove(last, it) }
    }

    /** Prepends a newer page above the loaded rows, with the same guard from the other side. */
    fun prependingNewer(
        page: List<MessageSummary>,
        rows: List<MessageSummary>,
    ): List<MessageSummary> {
        val first = rows.firstOrNull() ?: return page
        val ids = rows.mapTo(HashSet()) { it.id }
        return page.filter { it.id !in ids && sitsAbove(it, first) } + rows
    }

    /**
     * Rows of [current] named in [preserveIds] and absent from [fresh], merged back into it in the
     * server's own order before any windowing looks at it, or a preserved row sitting above the
     * fresh read's own edge would be treated as "not reached" and dropped anyway.
     */
    private fun withPreserved(
        fresh: List<MessageSummary>,
        current: List<MessageSummary>,
        preserveIds: Set<String>,
    ): List<MessageSummary> {
        if (preserveIds.isEmpty()) return fresh
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        val preserved = current.filter { it.id in preserveIds && it.id !in freshIds }
        if (preserved.isEmpty()) return fresh
        return (fresh + preserved).sortedWith { a, b ->
            when {
                a.id == b.id -> 0
                sitsAbove(a, b) -> -1
                else -> 1
            }
        }
    }
}
