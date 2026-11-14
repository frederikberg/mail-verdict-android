package com.frederikberg.mailverdict.kit.maillist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailListWindowTest {
    private fun ids(rows: List<com.frederikberg.mailverdict.contract.MessageSummary>) = rows.map { it.id }

    @Test
    fun newerRowsSitAboveOlderOnesAndTiesBreakOnId() {
        assertTrue(MailListWindow.sitsAbove(message(30), message(10)))
        assertFalse(MailListWindow.sitsAbove(message(10), message(30)))
        val a = message(5, receivedAt = "2026-11-01T10:00:00+00:00")
        val b = message(9, receivedAt = "2026-11-01T11:00:00+01:00")
        // The same instant written with two offsets is a tie; the higher id wins it.
        assertTrue(MailListWindow.sitsAbove(b, a))
    }

    @Test
    fun aMessageWithoutADateSitsAtTheTop() {
        assertTrue(MailListWindow.sitsAbove(message(1, receivedAt = null), message(99)))
        assertFalse(MailListWindow.sitsAbove(message(99), message(1, receivedAt = null)))
    }

    @Test
    fun refreshLimitIsTheWindowPlusSlackAndNeverMoreThanTheCeiling() {
        assertEquals(100, MailListWindow.refreshLimit(50))
        assertEquals(50, MailListWindow.refreshLimit(0))
        assertEquals(1000, MailListWindow.refreshLimit(5000))
    }

    @Test
    fun refreshedHeadIsSplicedOverTheWindowWithoutGrowingIt() {
        val current = rows(50 downTo 31)
        // Two arrivals on top, one row of the window gone from the middle, and a fresh read reaching past the old last row.
        val fresh =
            listOf(message(52), message(51)) + rows(50 downTo 31).filter { it.id != id(40) } + rows(30 downTo 28)
        val merged = MailListWindow.mergeRefreshed(current, fresh, freshHasMore = true, currentHasMore = true)
        assertEquals(
            ids(listOf(message(52), message(51)) + rows(50 downTo 31).filter { it.id != id(40) }),
            ids(merged.rows),
        )
        assertTrue(merged.hasMore)
    }

    @Test
    fun aFreshReadThatStopsAboveTheOldLastRowKeepsTheRowsBelowIt() {
        val current = rows(60 downTo 11)
        val fresh = listOf(message(61)) + rows(60 downTo 41)
        val merged = MailListWindow.mergeRefreshed(current, fresh, freshHasMore = true, currentHasMore = true)
        assertEquals(ids(listOf(message(61)) + rows(60 downTo 11)), ids(merged.rows))
    }

    @Test
    fun preservedRowsSurviveAFreshReadThatDroppedThem() {
        val current = rows(50 downTo 41)
        val fresh = rows(50 downTo 41).filter { it.id != id(45) }
        val merged = MailListWindow.mergeRefreshed(current, fresh, false, false, preserveIds = setOf(id(45)))
        assertTrue(id(45) in ids(merged.rows))
        assertEquals(ids(current), ids(merged.rows))
    }

    @Test
    fun aSavedCopyIsReplacedNotSpliced() {
        val saved = rows(50 downTo 1)
        val fresh = rows(80 downTo 31)
        val merged = MailListWindow.replacingSavedCopy(saved, fresh, freshHasMore = true)
        assertEquals(ids(fresh), ids(merged.rows))
        assertTrue(merged.hasMore)
    }

    @Test
    fun aWindowAwayFromTheNewestEdgeNeverTakesArrivalsAbove() {
        val current = rows(40 downTo 21)
        // Read upward from the window's last row: arrivals 45..41 sit above the window's first row and must be dropped.
        val freshAboveLast = rows(45 downTo 22)
        val merged = MailListWindow.mergeRefreshedFromBelow(current, freshAboveLast, freshHasMoreNewer = false)
        assertEquals(ids(rows(40 downTo 21)), ids(merged.rows))
        assertTrue("arrivals above stay a gap paging closes", merged.hasNewer)
    }

    @Test
    fun olderPageAppendsOnlyRowsThatSitBelowTheLastRow() {
        val rows = rows(50 downTo 31)
        // A page fetched against a cursor a refresh has since moved overlaps the window.
        val page = rows(35 downTo 20)
        assertEquals(ids(rows + rows(30 downTo 20)), ids(MailListWindow.appendingOlder(page, rows)))
    }

    @Test
    fun newerPagePrependsOnlyRowsThatSitAboveTheFirstRow() {
        val rows = rows(30 downTo 11)
        val page = rows(45 downTo 28)
        assertEquals(ids(rows(45 downTo 31) + rows), ids(MailListWindow.prependingNewer(page, rows)))
    }
}
