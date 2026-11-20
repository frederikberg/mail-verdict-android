package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WithdrawOnAlertChangesTest {
    private val server = TestServer()
    private val api = ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, OkHttpClient())

    @After
    fun stop() = server.close()

    private val live = "11111111-1111-1111-1111-111111111111"
    private val read = "22222222-2222-2222-2222-222222222222"

    private class FakeShown(
        private val tags: List<String>,
    ) : ShownNotifications {
        val cancelled = mutableListOf<String>()

        override fun shownTags() = tags

        override fun cancel(alertId: String) {
            cancelled += alertId
        }
    }

    private fun alert(
        id: String,
        dismissedAt: String?,
    ) = """{"id":"$id","kind":"mail","title":null,"body":null,"url":null,"account_id":null,"folder_id":null,
        |"message_id":null,"created_at":"2026-11-14T08:00:00Z","delivered_at":null,
        |"dismissed_at":${dismissedAt?.let { "\"$it\"" }}}
        """.trimMargin()

    private fun serveLookup() =
        server.script(
            "/api/alerts/lookup",
            TestServer.json("[${alert(live, null)},${alert(read, "2026-11-14T09:00:00Z")}]"),
        )

    private fun run(
        shown: FakeShown,
        vararg batches: List<LiveInvalidation>,
        failures: MutableList<ApiError> = mutableListOf(),
    ) = runBlocking {
        withdrawOnAlertChanges(
            flowOf(*batches.map { batch -> batch.map { LiveEvent(it, 0) } }.toTypedArray()),
            onFailure = { failures += it },
        ) { withdrawStaleNotifications(api, shown) }
    }

    @Test
    fun aDismissalOnTheStreamWithdrawsTheBannerOfMailReadElsewhere() {
        serveLookup()
        val shown = FakeShown(listOf(live, read))
        run(shown, listOf(LiveInvalidation.AlertsChanged))
        assertEquals(listOf(read), shown.cancelled)
    }

    @Test
    fun aResyncWithdrawsToo() {
        serveLookup()
        val shown = FakeShown(listOf(live, read))
        run(shown, listOf(LiveInvalidation.Resync))
        assertEquals(listOf(read), shown.cancelled)
    }

    @Test
    fun aStreamEventAboutSomethingElseAsksNothing() {
        val shown = FakeShown(listOf(live, read))
        run(shown, listOf(LiveInvalidation.FoldersChanged, LiveInvalidation.NotificationsChanged))
        assertTrue(server.requestsTo("/api/alerts/lookup").isEmpty())
        assertTrue(shown.cancelled.isEmpty())
    }

    @Test
    fun aFailedLookupIsReportedAndTheNextTriggerTriesAgain() {
        server.script("/api/alerts/lookup", TestServer.jsonError(500, "broken"))
        val shown = FakeShown(listOf(read))
        val failures = mutableListOf<ApiError>()
        run(shown, listOf(LiveInvalidation.AlertsChanged), failures = failures)
        assertEquals(1, failures.size)
        assertTrue(shown.cancelled.isEmpty())
        serveLookup()
        run(shown, listOf(LiveInvalidation.AlertsChanged))
        assertEquals(listOf(read), shown.cancelled)
    }

    @Test
    fun noBannerOnScreenMeansNoLookup() {
        val shown = FakeShown(emptyList())
        run(shown, listOf(LiveInvalidation.AlertsChanged))
        assertTrue(server.requestsTo("/api/alerts/lookup").isEmpty())
    }
}
