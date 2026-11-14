package com.frederikberg.mailverdict.kit.net

/**
 * The name this phone gives itself in the server's list of signed-in devices, sent as
 * [HEADER] on every API request.
 */
object DeviceName {
    const val HEADER = "X-Device-Name"

    /** The longest name the server accepts, in characters. */
    const val MAX_LENGTH = 64

    private const val FALLBACK = "Android device"

    /** [raw] with control characters and runs of whitespace collapsed to single spaces and cut to [MAX_LENGTH] characters; a blank name becomes a generic one. */
    fun sanitize(raw: String?): String {
        val cleaned =
            raw
                .orEmpty()
                .map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }
                .joinToString("")
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        if (cleaned.isEmpty()) return FALLBACK
        val end = cleaned.offsetByCodePoints(0, minOf(MAX_LENGTH, cleaned.codePointCount(0, cleaned.length)))
        return cleaned.substring(0, end).trim()
    }
}
