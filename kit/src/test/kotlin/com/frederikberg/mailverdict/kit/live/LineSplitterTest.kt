package com.frederikberg.mailverdict.kit.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineSplitterTest {
    private fun LineSplitter.feed(text: String) = ingest(text.toByteArray())

    /** The one rule a generic line reader gets wrong here: a blank line terminates a record, so dropping it leaves every event unterminated. */
    @Test
    fun blankLinesSurvive() {
        assertEquals(
            listOf("event: a", "data: 1", "", "event: b", "data: 2", ""),
            LineSplitter().feed("event: a\ndata: 1\n\nevent: b\ndata: 2\n\n"),
        )
    }

    /** A `\r` at the end of a chunk may be the first half of a `\r\n` the next chunk completes: the line count must not change with where the network cut. */
    @Test
    fun aCarriageReturnAtAChunkEndDoesNotSplitALineInTwo() {
        val splitter = LineSplitter()
        val lines = splitter.feed("data: hello\r") + splitter.feed("\ndata: next\r\n")
        assertEquals(listOf("data: hello", "data: next"), lines)
    }

    @Test
    fun everyCutOfAStreamYieldsTheSameLines() {
        val stream = "id: 1\r\nevent: a\r\ndata: x\r\n\r\nevent: b\rdata: y\n\n".toByteArray()
        val whole = LineSplitter().ingest(stream)
        assertEquals(listOf("id: 1", "event: a", "data: x", "", "event: b", "data: y", ""), whole)
        for (cut in 1 until stream.size) {
            val splitter = LineSplitter()
            val lines =
                splitter.ingest(stream.copyOfRange(0, cut)) + splitter.ingest(stream.copyOfRange(cut, stream.size))
            assertEquals("cut at $cut", whole, lines)
        }
    }

    @Test
    fun aMultiByteCharacterSplitAcrossChunksStaysWhole() {
        val bytes = "data: ä€\n".toByteArray()
        val splitter = LineSplitter()
        val lines = splitter.ingest(bytes.copyOfRange(0, 7)) + splitter.ingest(bytes.copyOfRange(7, bytes.size))
        assertEquals(listOf("data: ä€"), lines)
    }

    @Test
    fun finishFlushesAnUnterminatedTrailingLineOnce() {
        val splitter = LineSplitter()
        splitter.feed("data: complete\n")
        splitter.feed("data: no terminator yet")
        assertEquals("data: no terminator yet", splitter.finish())
        assertNull(splitter.finish())
    }
}
