package com.frederikberg.mailverdict.kit.push

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

class ReadSyncTest {
    private val server = TestServer()
    private val api = ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, OkHttpClient())

    @After
    fun stop() = server.close()

    private val live = "11111111-1111-1111-1111-111111111111"
    private val dismissed = "22222222-2222-2222-2222-222222222222"
    private val forgotten = "33333333-3333-3333-3333-333333333333"

    private fun alert(
        id: String,
        dismissedAt: String?,
    ) = """{"id":"$id","kind":"mail","title":null,"body":null,"url":null,"account_id":null,"folder_id":null,
        |"message_id":null,"created_at":"2026-11-14T08:00:00Z","delivered_at":null,
        |"dismissed_at":${dismissedAt?.let { "\"$it\"" }}}
        """.trimMargin()

    @Test
    fun aNotificationForADismissedOrForgottenAlertIsStale() {
        server.script(
            "/api/alerts/lookup",
            TestServer.json("[${alert(live, null)},${alert(dismissed, "2026-11-14T09:00:00Z")}]"),
        )

        val stale = runBlocking { staleAlertIds(api, listOf(live, dismissed, forgotten)) }

        assertEquals(listOf(dismissed, forgotten), stale)
    }

    @Test
    fun onlyAlertIdsAreAsked() {
        server.script("/api/alerts/lookup", TestServer.json("[]"))

        val stale = runBlocking { staleAlertIds(api, listOf("/?message=abc", live)) }

        assertEquals(listOf(live), stale)
        assertTrue(
            "/?message=abc" !in
                server
                    .requestsTo("/api/alerts/lookup")
                    .single()
                    .body!!
                    .utf8(),
        )
    }

    @Test
    fun nothingShownAsksNothing() {
        assertEquals(emptyList<String>(), runBlocking { staleAlertIds(api, emptyList()) })
        assertTrue(server.requests.isEmpty())
    }
}
