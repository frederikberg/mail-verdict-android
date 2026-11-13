package com.frederikberg.mailverdict.kit

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * A real HTTP server on loopback that answers each path from a queue of scripted responses and
 * remembers every request. The last scripted answer of a path repeats, so a test only scripts what
 * changes. A path with nothing scripted answers 404, which is what a test that forgot a route sees.
 */
class TestServer : AutoCloseable {
    private val scripts = HashMap<String, ArrayDeque<MockResponse>>()
    private val seen = ConcurrentLinkedQueue<RecordedRequest>()
    private val server = MockWebServer()

    val requests: List<RecordedRequest> get() = seen.toList()

    /** The server's own address without a trailing slash: the base URL of an API client and the issuer of a provider. */
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen.add(request)
                    val path = request.url.encodedPath
                    synchronized(scripts) {
                        val queue = scripts[path] ?: return notFound()
                        return if (queue.size > 1) queue.removeFirst() else queue.first()
                    }
                }
            }
        server.start()
    }

    fun script(
        path: String,
        vararg responses: MockResponse,
    ) = synchronized(scripts) {
        scripts.getOrPut(path) { ArrayDeque() }.apply {
            clear()
            addAll(responses)
        }
    }

    fun requestsTo(path: String): List<RecordedRequest> = requests.filter { it.url.encodedPath == path }

    override fun close() = server.close()

    private fun notFound() =
        MockResponse
            .Builder()
            .code(404)
            .body("""{"detail":"not scripted"}""")
            .build()

    companion object {
        fun json(
            body: String,
            code: Int = 200,
            delayMs: Long = 0,
        ): MockResponse =
            MockResponse
                .Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .apply { if (delayMs > 0) bodyDelay(delayMs, TimeUnit.MILLISECONDS) }
                .build()

        fun jsonError(
            code: Int,
            detail: String,
        ) = json("""{"detail":"$detail"}""", code)

        /** What the login proxy answers an invalid bearer with: a bare, non-JSON 403. */
        fun proxyForbidden(): MockResponse =
            MockResponse
                .Builder()
                .code(403)
                .addHeader("Content-Type", "text/plain")
                .body("Forbidden")
                .build()

        fun status(code: Int): MockResponse = MockResponse.Builder().code(code).build()
    }
}
