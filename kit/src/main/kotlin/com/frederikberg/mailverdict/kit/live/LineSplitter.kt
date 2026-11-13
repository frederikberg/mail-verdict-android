package com.frederikberg.mailverdict.kit.live

import java.io.ByteArrayOutputStream

/**
 * Splits a raw byte stream into lines, blank lines included: in `text/event-stream` a blank line is
 * the record terminator, so a splitter that drops it leaves every record unterminated and no event is
 * ever decoded.
 *
 * `\n`, `\r\n` and a lone `\r` all end a line. A `\r` at the very end of a chunk is held back, since
 * the next chunk may complete it into `\r\n`: the network splits a stream anywhere, inside a two-byte
 * terminator included. A multi-byte character split across chunks stays intact because bytes are
 * buffered until their line ends and only then decoded.
 */
class LineSplitter {
    private var buffer = ByteArrayOutputStream()
    private var heldCarriageReturn = false

    /** Feeds one chunk and returns every line it completes, in order. */
    fun ingest(chunk: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        var index = 0
        if (heldCarriageReturn && chunk.isNotEmpty()) {
            heldCarriageReturn = false
            if (chunk[0] == LF) index = 1
        }
        while (index < chunk.size) {
            val byte = chunk[index]
            when {
                byte == LF -> {
                    lines += takeLine()
                    index++
                }
                byte == CR -> {
                    lines += takeLine()
                    if (index + 1 < chunk.size) {
                        index += if (chunk[index + 1] == LF) 2 else 1
                    } else {
                        heldCarriageReturn = true
                        index++
                    }
                }
                else -> {
                    buffer.write(byte.toInt())
                    index++
                }
            }
        }
        return lines
    }

    /** Flushes a trailing line left when the connection ends without a final terminator; null when nothing was pending. */
    fun finish(): String? {
        heldCarriageReturn = false
        if (buffer.size() == 0) return null
        return takeLine()
    }

    private fun takeLine(): String {
        val line = buffer.toByteArray().decodeToString()
        buffer = ByteArrayOutputStream()
        return line
    }

    private companion object {
        const val LF = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
    }
}
