package com.frederikberg.mailverdict.kit.maillist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MailRowsTest {
    private val factory = MailRowFactory(ZoneId.of("UTC")) { Instant.parse("2026-11-14T12:00:00Z") }

    @Test
    fun aRowCarriesWhatTheViewDrawsAndNothingTheViewMustCompute() {
        val row =
            factory.make(
                message(7, seen = false, subject = "  Quarterly\n  numbers ", snippet = "Hello\n\nthere"),
            )
        assertEquals("Sender 7", row.sender)
        assertEquals("Quarterly numbers", row.subject)
        assertEquals("Hello there", row.snippet)
        assertTrue(row.isUnread)
        assertEquals(1, row.threadCount)
    }

    @Test
    fun aMissingSubjectIsSaidSo() = assertEquals("(no subject)", factory.make(message(1, subject = null)).subject)

    @Test
    fun aHugeTextIsCutBeforeLayoutAndSaysSo() {
        val row =
            factory.make(
                message(1, subject = "S".repeat(20_000), snippet = "x ".repeat(20_000), from = "F".repeat(5_000)),
            )
        assertEquals(MailRowFactory.SUBJECT_MAX, row.subject.length)
        assertTrue(row.subject.endsWith("…"))
        assertTrue(row.snippet.length <= MailRowFactory.SNIPPET_MAX && row.snippet.endsWith("…"))
        assertTrue(row.sender.length <= MailRowFactory.SENDER_MAX)
    }

    @Test
    fun aTextWithinTheBoundIsLeftAlone() {
        val subject = "s".repeat(MailRowFactory.SUBJECT_MAX)
        assertEquals(subject, factory.make(message(1, subject = subject)).subject)
    }

    @Test
    fun aSentFolderShowsWhoTheMailWentTo() {
        val row = factory.make(message(1), showRecipients = true)
        assertEquals("To: me", row.sender)
    }

    @Test
    fun pagingLoadsTwoScreensBeforeEitherEdgeAndNeverAtIt() {
        // Ten rows on a screen, 200 loaded.
        assertEquals(
            PagingNeeds(older = false, newer = false),
            PagingNeeds.of(firstVisible = 90, lastVisible = 99, visibleCount = 10, total = 200),
        )
        assertEquals(
            PagingNeeds(older = true, newer = false),
            PagingNeeds.of(firstVisible = 171, lastVisible = 180, visibleCount = 10, total = 200),
        )
        assertEquals(
            PagingNeeds(older = false, newer = true),
            PagingNeeds.of(firstVisible = 19, lastVisible = 28, visibleCount = 10, total = 200),
        )
        assertEquals(PagingNeeds(older = false, newer = false), PagingNeeds.of(0, -1, 0, 0))
    }
}
