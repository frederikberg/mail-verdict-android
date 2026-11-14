package com.frederikberg.mailverdict.kit.alerts

import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsStoreTest {
    private val server = TestServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun store(subscription: String? = null) =
        AlertsStore(ApiClient(RequestFactory(server.baseUrl) { AuthMode.None }, OkHttpClient()), scope) { subscription }

    private fun alert(
        id: String,
        kind: String,
        message: String? = null,
    ) = """{"id":"$id","kind":"$kind","title":"t","body":null,"url":null,"account_id":"acc","folder_id":"fol",
        |"message_id":${message?.let {
        "\"$it\""
    }},"created_at":"2026-11-14T08:00:00Z","delivered_at":null,"dismissed_at":null}
        """.trimMargin()

    private fun serve(vararg alerts: String) {
        server.script("/api/alerts", TestServer.json("[${alerts.joinToString(",")}]"))
        server.script("/api/alerts/badge", TestServer.json("""{"count":${alerts.size}}"""))
    }

    @Test
    fun mailAndEveryOtherKindLandInTheirOwnLists() =
        runBlocking {
            serve(alert("1", "mail", "m1"), alert("2", "outbox_stalled"), alert("3", "webhook_failed"))
            val store = store()
            store.load()
            assertEquals(
                listOf("1"),
                store.state.value.mailAlerts
                    .map { it.id },
            )
            assertEquals(
                listOf("2", "3"),
                store.state.value.systemAlerts
                    .map { it.id },
            )
            assertEquals(listOf("outbox_stalled", "webhook_failed"), store.state.value.systemKinds)
            assertEquals(
                "unseen only",
                "true",
                server
                    .requestsTo("/api/alerts")
                    .single()
                    .url
                    .queryParameter("unseen_only"),
            )
        }

    @Test
    fun theBellCountComesFromTheServerWithThisDevicesOwnScope() =
        runBlocking {
            serve(alert("1", "mail", "m1"))
            val store = store(subscription = "sub-7")
            store.load()
            assertEquals(1, store.badge.value)
            assertEquals(
                "sub-7",
                server
                    .requestsTo("/api/alerts/badge")
                    .single()
                    .url
                    .queryParameter("subscription_id"),
            )
        }

    @Test
    fun dismissingRemovesTheAlertAndAsksTheBellAgain() =
        runBlocking {
            serve(alert("1", "mail", "m1"), alert("2", "mail", "m2"))
            val store = store()
            store.load()
            server.script(
                "/api/alerts/1/dismiss",
                mockwebserver3.MockResponse
                    .Builder()
                    .code(204)
                    .build(),
            )
            server.script("/api/alerts/badge", TestServer.json("""{"count":1}"""))
            store.dismiss("1")
            assertEquals(
                listOf("2"),
                store.state.value.mailAlerts
                    .map { it.id },
            )
            assertEquals(1, store.badge.value)
        }

    @Test
    fun anAlertTheServerRefusesToDismissStaysInTheList() =
        runBlocking {
            serve(alert("1", "mail", "m1"))
            val store = store()
            store.load()
            server.script("/api/alerts/1/dismiss", TestServer.jsonError(500, "broken"))
            store.dismiss("1")
            assertEquals(
                listOf("1"),
                store.state.value.mailAlerts
                    .map { it.id },
            )
            assertEquals("broken", store.state.value.errorMessage)
        }

    @Test
    fun dismissAllOnTheSystemListSendsEveryKindItHolds() =
        runBlocking {
            serve(alert("1", "mail", "m1"), alert("2", "outbox_stalled"), alert("3", "webhook_failed"))
            val store = store()
            store.load()
            server.script(
                "/api/alerts/dismiss-all",
                mockwebserver3.MockResponse
                    .Builder()
                    .code(204)
                    .build(),
            )
            store.dismissAllSystem()
            assertEquals(
                listOf("outbox_stalled", "webhook_failed"),
                server
                    .requestsTo("/api/alerts/dismiss-all")
                    .single()
                    .url
                    .queryParameterValues("kind"),
            )
            assertTrue(
                store.state.value.systemAlerts
                    .isEmpty(),
            )
            assertEquals(1, store.state.value.mailAlerts.size)
        }

    @Test
    fun openingAnAlertDismissesItAndNamesItsMessage() =
        runBlocking {
            serve(alert("1", "mail", "m1"), alert("2", "outbox_stalled"))
            val store = store()
            store.load()
            server.script(
                "/api/alerts/1/dismiss",
                mockwebserver3.MockResponse
                    .Builder()
                    .code(204)
                    .build(),
            )
            server.script(
                "/api/alerts/2/dismiss",
                mockwebserver3.MockResponse
                    .Builder()
                    .code(204)
                    .build(),
            )
            val target =
                store.open(
                    store.state.value.mailAlerts
                        .single(),
                )
            assertEquals("m1", target)
            assertNull(
                "a system alert names no message",
                store.open(
                    store.state.value.systemAlerts
                        .single(),
                ),
            )
            assertEquals(1, server.requestsTo("/api/alerts/1/dismiss").size)
        }

    @Test
    fun openingAnAlertThatWasAlreadyDismissedDoesNotDismissItAgain() =
        runBlocking {
            serve(alert("1", "mail", "m1"))
            val store = store()
            store.load()
            val seen =
                store.state.value.mailAlerts
                    .single()
                    .copy(dismissedAt = "2026-11-14T09:00:00Z")
            assertEquals("m1", store.open(seen))
            assertTrue(server.requestsTo("/api/alerts/1/dismiss").isEmpty())
        }

    @Test
    fun anAlertEventRefreshesTheBellAndTheListOnlyOnceItWasOpened() =
        runBlocking {
            serve(alert("1", "mail", "m1"))
            val store = store()
            val events = MutableSharedFlow<List<LiveEvent>>(extraBufferCapacity = 4)
            store.follow(events)
            delay(100)
            events.emit(listOf(LiveEvent(LiveInvalidation.AlertsChanged, 0)))
            withTimeout(5_000) { while (server.requestsTo("/api/alerts/badge").isEmpty()) delay(20) }
            assertTrue("the list was never opened, so it is not read", server.requestsTo("/api/alerts").isEmpty())
            store.load()
            events.emit(listOf(LiveEvent(LiveInvalidation.AlertsChanged, 0)))
            withTimeout(5_000) { while (server.requestsTo("/api/alerts").size < 2) delay(20) }
        }
}
