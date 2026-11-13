package com.frederikberg.mailverdict.kit.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseEventAccumulatorTest {
    private fun SseEventAccumulator.feed(vararg lines: String): SseRecord? = lines.map { ingest(it) }.lastOrNull()

    @Test
    fun aCompleteRecordIsEmittedOnTheBlankLine() {
        val accumulator = SseEventAccumulator()
        assertNull(accumulator.feed("id: a1b2-7", "event: verdict.issued", """data: {"mail_id":"1"}"""))
        assertEquals(SseRecord("a1b2-7", "verdict.issued", """{"mail_id":"1"}"""), accumulator.ingest(""))
    }

    /** Several `data:` lines join with a newline, not bare: a wrapped payload must come back with its line breaks. */
    @Test
    fun multipleDataLinesJoinWithNewlines() {
        val accumulator = SseEventAccumulator()
        accumulator.feed("event: resync", "data: line one", "data: line two")
        assertEquals("line one\nline two", accumulator.ingest("")?.data)
    }

    /** The opening padding comment and keep-alives carry no event and must not be glued onto the record being built. */
    @Test
    fun aCommentLineIsIgnored() {
        val accumulator = SseEventAccumulator()
        accumulator.feed("event: connected")
        assertNull(accumulator.ingest(": keepalive"))
        accumulator.feed("data: {}")
        assertEquals("{}", accumulator.ingest("")?.data)
    }

    @Test
    fun aRecordWithoutAnEventNameOrWithoutDataIsDiscarded() {
        val nameless = SseEventAccumulator()
        nameless.feed("data: orphaned")
        assertNull(nameless.ingest(""))

        val dataless = SseEventAccumulator()
        dataless.feed("event: empty")
        assertNull(dataless.ingest(""))
    }

    /** State resets after each record, so the next one does not inherit the previous record's id or name. */
    @Test
    fun stateResetsBetweenRecords() {
        val accumulator = SseEventAccumulator()
        accumulator.feed("id: a1b2-1", "event: first", "data: one")
        accumulator.ingest("")
        accumulator.feed("event: second", "data: two")
        assertEquals(SseRecord(null, "second", "two"), accumulator.ingest(""))
    }

    /** Only one leading space after `data:` is framing; the rest is payload. */
    @Test
    fun onlyOneLeadingSpaceIsStrippedFromData() {
        val accumulator = SseEventAccumulator()
        accumulator.feed("event: x", "data:  two spaces")
        assertEquals(" two spaces", accumulator.ingest("")?.data)
        accumulator.feed("event: x", "data:none")
        assertEquals("none", accumulator.ingest("")?.data)
    }
}
