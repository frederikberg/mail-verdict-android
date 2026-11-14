package com.frederikberg.mailverdict.kit.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MailFormatTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-14T12:00:00Z")

    private fun date(iso: String?) = MailFormat.listDate(iso, now, zone)

    @Test
    fun todayIsATimeInTheDeviceZone() = assertEquals("14:30", date("2026-11-14T13:30:00+00:00"))

    @Test
    fun yesterdayAndTheWeekdaysBeforeIt() {
        assertEquals("Yesterday", date("2026-11-13T09:00:00+00:00"))
        assertEquals("Wed", date("2026-11-11T09:00:00+00:00"))
    }

    @Test
    fun olderMailIsADayFirstDateWithTheYearOnlyWhenItIsNotThisOne() {
        assertEquals("2 Nov", date("2026-11-02T09:00:00+00:00"))
        assertEquals("30 Dec 2025", date("2025-12-30T09:00:00+00:00"))
    }

    @Test
    fun aFutureDateIsNeverABareWeekday() = assertEquals("20 Nov", date("2026-11-20T09:00:00+00:00"))

    @Test
    fun anUnreadableOrMissingDateIsEmpty() {
        assertEquals("", date(null))
        assertEquals("", date("last tuesday"))
    }

    @Test
    fun senderNamesComeFromTheDisplayNameOrTheLocalPart() {
        assertEquals("Ada Lovelace", MailFormat.senderName("\"Ada Lovelace\" <ada@example.com>"))
        assertEquals("ada", MailFormat.senderName("ada@example.com"))
        assertEquals("Unknown", MailFormat.senderName(null))
        assertEquals("ada@example.com", MailFormat.email("Ada <ada@example.com>"))
        assertEquals("ada@example.com", MailFormat.email("ada@example.com"))
    }

    @Test
    fun sizesAreHumanReadable() {
        assertEquals("0 B", MailFormat.size(null))
        assertEquals("512 B", MailFormat.size(512))
        assertEquals("1.5 KB", MailFormat.size(1536))
        assertEquals("2.0 MB", MailFormat.size(2L * 1024 * 1024))
    }
}
