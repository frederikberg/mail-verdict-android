package com.frederikberg.mailverdict.kit.search

import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ApiSearchBackendTest {
    private val server = TestServer()

    @After
    fun stop() = server.close()

    private val backend =
        ApiSearchBackend(
            ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, OkHttpClient()),
            now = { Instant.parse("2026-11-14T10:30:00Z") },
            zone = { ZoneId.of("UTC") },
        )

    private fun result(
        id: String,
        extra: String = "",
    ) = """{"id":"$id","account_id":"a","folder_id":"f","thread_id":"t","mirrored_at":"2026-11-01T00:00:00Z",
        |"has_attachments":false$extra}
        """.trimMargin()

    @Test
    fun aTextSearchSendsFieldsFoldersAndTheResolvedWindow() {
        server.script(
            "/api/search",
            TestServer.json(
                """{"query":"x","results":[${result("1")}],"total":1,"has_more":true,"next_cursor":"c9"}""",
            ),
        )
        val outcome =
            runBlocking {
                backend.search(
                    SearchContext(
                        query = " invoice ",
                        accountId = "acc",
                        folderIds = listOf("f1", "f2"),
                        fields = listOf(SearchField.Subject, SearchField.From),
                        timeWindow = TimeWindow.Last7Days,
                    ),
                    before = "c8",
                )
            }
        val url = server.requestsTo("/api/search").single().url
        assertEquals("invoice", url.queryParameter("q"))
        assertEquals("acc", url.queryParameter("account_id"))
        assertEquals(listOf("f1", "f2"), url.queryParameterValues("folder_ids"))
        assertEquals(listOf("subject", "from"), url.queryParameterValues("fields"))
        assertEquals("c8", url.queryParameter("before"))
        assertEquals("2026-11-07T10:30:00Z", url.queryParameter("received_after"))
        assertEquals("2026-11-14T10:30:00Z", url.queryParameter("received_before"))
        outcome as SearchOutcome.Page
        assertEquals("c9", outcome.nextCursor)
        assertTrue(outcome.hasMore)
        assertEquals(null, outcome.hits.single().tier)
    }

    @Test
    fun aSemanticSearchKeepsTheTierOfEachHitAndNeverPages() {
        server.script(
            "/api/embeddings/search",
            TestServer.json(
                """{"query":"x","model":"m","results":[${result(
                    "1",
                    ""","tier":1""",
                )},${result("2", ""","tier":2""")}]}""",
            ),
        )
        val outcome =
            runBlocking { backend.search(SearchContext(mode = SearchMode.Semantic, query = "contract"), null) }
        outcome as SearchOutcome.Page
        assertEquals(listOf(1, 2), outcome.hits.map { it.tier })
        assertEquals(false, outcome.hasMore)
        assertEquals(
            null,
            server
                .requestsTo("/api/embeddings/search")
                .single()
                .url
                .queryParameter("fields"),
        )
    }

    @Test
    fun aServerWithoutAnEmbeddingProviderIsTheSemanticFallbackCase() {
        server.script("/api/embeddings/search", TestServer.jsonError(503, "semantic_unavailable"))
        val outcome =
            runBlocking { backend.search(SearchContext(mode = SearchMode.Semantic, query = "contract"), null) }
        outcome as SearchOutcome.Failure
        assertTrue(outcome.semanticUnavailable)
    }

    @Test
    fun aTextSearchThatAnswers503IsAnOrdinaryFailure() {
        server.script("/api/search", TestServer.jsonError(503, "busy"))
        val outcome = runBlocking { backend.search(SearchContext(query = "invoice"), null) }
        outcome as SearchOutcome.Failure
        assertEquals(false, outcome.semanticUnavailable)
        assertEquals("busy", outcome.message)
    }
}
