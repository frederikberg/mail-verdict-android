package com.frederikberg.mailverdict.kit.net

/**
 * How [ApiClient] asks again when a read (`GET`) fails for a reason that passes in a moment: a
 * phone switching networks, a keep-alive connection the server already closed, a proxy answering for
 * a backend that is restarting. Only reads are repeated: they change nothing on the server, so asking
 * twice is harmless. A write that may have landed is the action queue's business, which knows more.
 */
data class ReadRetryPolicy(
    /** One more attempt per entry, after waiting that many milliseconds. */
    val delaysMs: List<Long> = listOf(250, 1_000),
    /** How long a whole read may take before it gives up, so a dead link becomes the offline fallback rather than a minute of spinner. */
    val readTimeoutMs: Long = 30_000,
) {
    companion object {
        /** 502 and 504: the proxy is up, the backend behind it is not, yet. A 503 is the server saying so on purpose. */
        fun isTransient(statusCode: Int): Boolean = statusCode == 502 || statusCode == 504
    }
}
