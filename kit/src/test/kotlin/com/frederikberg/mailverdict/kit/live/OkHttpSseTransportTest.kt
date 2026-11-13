package com.frederikberg.mailverdict.kit.live

import com.frederikberg.mailverdict.kit.TestServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** The one place the event stream touches a real socket: what the scripted transport cannot vouch for. */
class OkHttpSseTransportTest {
    private val server = TestServer()
    private val transport = OkHttpSseTransport(OkHttpClient())

    @After
    fun stop() = server.close()

    private fun request(path: String = "/api/events") =
        Request
            .Builder()
            .url(
                server.baseUrl + path,
            ).header("Accept", "text/event-stream")
            .header("Last-Event-ID", "e1-4")
            .build()

    private fun stream(body: String): MockResponse =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "text/event-stream")
            .body(body)
            .build()

    @Test
    fun aStreamIsReadToItsEndAndTheRequestKeepsItsHeaders() =
        runBlocking {
            server.script("/api/events", stream(": padding\n\nid: e1-5\nevent: mail.new\ndata: {}\n\n"))
            val connection = transport.open(request())
            val text = StringBuilder()
            connection.use {
                while (true) text.append((it.readChunk() ?: break).decodeToString())
            }
            assertEquals(200, connection.status)
            assertTrue(text.toString(), "event: mail.new" in text)
            val sent = server.requestsTo("/api/events").single()
            assertEquals("e1-4", sent.headers["Last-Event-ID"])
            assertEquals("text/event-stream", sent.headers["Accept"])
        }

    /** The stream cannot tell the proxy's bare 403 from the app's by status, so the body of a refusal must reach the client. */
    @Test
    fun aRefusalCarriesItsStatusAndBody() =
        runBlocking {
            server.script("/api/events", TestServer.proxyForbidden())
            transport.open(request()).use {
                assertEquals(403, it.status)
                assertEquals("Forbidden", it.errorBody.decodeToString())
            }
            server.script("/api/events", TestServer.jsonError(403, "user_disabled"))
            transport.open(request()).use { assertTrue(it.errorBody.decodeToString().contains("user_disabled")) }
        }

    @Test
    fun aServerThatIsNotThereIsAnIoError() {
        val freePort = ServerSocket(0).use { it.localPort }
        val error =
            runCatching {
                runBlocking {
                    transport.open(
                        Request.Builder().url("http://127.0.0.1:$freePort/api/events").build(),
                    )
                }
            }.exceptionOrNull()
        assertTrue("$error", error is IOException)
    }

    /** The watchdog cancels a read on a silent connection; only closing the call frees the blocked socket read, so cancelling must return promptly. */
    @Test
    fun cancellingAReadOnASilentStreamReturnsPromptly() =
        runBlocking {
            server.script(
                "/api/events",
                MockResponse
                    .Builder()
                    .code(
                        200,
                    ).addHeader(
                        "Content-Type",
                        "text/event-stream",
                    ).body(": hello\n\n")
                    .bodyDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            val connection = transport.open(request())
            val read = async(Dispatchers.Default) { connection.readChunk() }
            delay(300)
            val started = System.nanoTime()
            read.cancel()
            val outcome = runCatching { withTimeout(5_000) { read.await() } }.exceptionOrNull()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            connection.close()
            assertTrue("$outcome", outcome is CancellationException)
            assertTrue("took ${tookMs}ms", tookMs < 3_000)
        }
}
