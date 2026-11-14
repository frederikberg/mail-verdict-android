package com.frederikberg.mailverdict.kit.search

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class SearchSupportTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-14T10:30:00Z") // 11:30 in Berlin

    @Test
    fun todayStartsAtLocalMidnightNotUtcMidnight() {
        val (since, until) = TimeWindow.Today.bounds(now, berlin)
        assertEquals(Instant.parse("2026-11-13T23:00:00Z"), since)
        assertEquals(now, until)
    }

    @Test
    fun sinceYesterdayStartsAtYesterdaysLocalMidnight() {
        assertEquals(Instant.parse("2026-11-12T23:00:00Z"), TimeWindow.SinceYesterday.bounds(now, berlin).first)
    }

    @Test
    fun theRollingPresetsCountBackFromNow() {
        assertEquals(Instant.parse("2026-11-07T10:30:00Z"), TimeWindow.Last7Days.bounds(now, berlin).first)
        assertEquals(Instant.parse("2026-10-15T10:30:00Z"), TimeWindow.Last30Days.bounds(now, berlin).first)
    }

    @Test
    fun aDayAcrossTheClockChangeStillStartsAtLocalMidnight() {
        // 2026-10-25 is the day Berlin leaves summer time: its midnight is 22:00Z, not 23:00Z.
        val sunday = Instant.parse("2026-10-25T12:00:00Z")
        assertEquals(Instant.parse("2026-10-24T22:00:00Z"), TimeWindow.Today.bounds(sunday, berlin).first)
    }

    @Test
    fun aSenderShowsAsItsNameOrElseItsAddress() {
        assertEquals("Ada Lovelace", SearchRowFormat.senderLabel("\"Ada Lovelace\" <ada@example.com>"))
        assertEquals("Ada", SearchRowFormat.senderLabel("Ada <ada@example.com>"))
        assertEquals("ada@example.com", SearchRowFormat.senderLabel("<ada@example.com>"))
        assertEquals("ada@example.com", SearchRowFormat.senderLabel("ada@example.com"))
        assertEquals("Unknown sender", SearchRowFormat.senderLabel(null))
    }

    @Test
    fun aDateShowsTheTimeTodayThenTheDayThenTheFullDate() {
        val us = Locale.US
        assertEquals(
            "9:15 AM",
            SearchRowFormat.dateLabel("2026-11-14T08:15:00+00:00", now, berlin, us).replace(' ', ' '),
        )
        assertEquals("3 Mar", SearchRowFormat.dateLabel("2026-03-03T08:15:00+00:00", now, berlin, us))
        assertEquals("12/31/25", SearchRowFormat.dateLabel("2025-12-31T08:15:00+00:00", now, berlin, us))
        assertEquals("", SearchRowFormat.dateLabel("not a date", now, berlin, us))
        assertEquals("", SearchRowFormat.dateLabel(null, now, berlin, us))
    }

    @Test
    fun aSnippetCollapsesItsLineBreaks() {
        val hit =
            SearchHit(
                "1",
                "a",
                "f",
                "t",
                " Invoice ",
                "x@example.com",
                null,
                false,
                false,
                true,
                false,
                "line one\n\n  line   two",
                3,
                true,
            )
        val row = SearchRowFormat.row(hit, now, berlin, Locale.US)
        assertEquals(listOf(SnippetRun("line one line two", false)), row.snippet)
        assertEquals("Invoice", row.subject)
        assertEquals(3, row.threadCount)
        assertEquals(true, row.isUnread)
        assertEquals(true, row.isSpam)
    }

    @Test
    fun matchedWordsInASnippetBecomeMarkedRuns() {
        assertEquals(
            listOf(SnippetRun("Please pay the ", false), SnippetRun("invoice", true), SnippetRun(" today", false)),
            SearchRowFormat.snippetRuns("Please pay the **invoice** today"),
        )
        assertEquals(listOf(SnippetRun("a ** b", false)), SearchRowFormat.snippetRuns("a ** b"))
        assertEquals(
            listOf(SnippetRun("one", true), SnippetRun("two", true)),
            SearchRowFormat.snippetRuns("**one****two**"),
        )
        assertEquals(emptyList<SnippetRun>(), SearchRowFormat.snippetRuns(null))
    }
}
