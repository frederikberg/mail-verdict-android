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
        AlertsStore(
            ApiClient(
                RequestFactory(server.baseUrl, "Test phone") {
                    AuthMode.None
                },
                OkHttpClient(),
            ),
            scope,
        ) { subscription }

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

    private fun notification(
        id: Int,
        account: String = "acc-1",
        action: String = "move",
        reverted: Boolean = false,
    ) = """{"id":$id,"account_id":"$account","action":"$action","message_id":null,"folder_id":null,
        |"outbox_id":null,"error":"550 mailbox full","detail":{"subject":"Quarterly report"},
        |"acknowledged_at":null,"reverted_at":${if (reverted) "\"2026-11-14T09:00:00Z\"" else "null"},
        |"created_at":"2026-11-14T08:00:00Z"}
        """.trimMargin()

    private fun serveNotifications(vararg notifications: String) {
        server.script("/api/notifications", TestServer.json("[${notifications.joinToString(",")}]"))
        server.script(
            "/api/accounts",
            TestServer.json(
                """[{"id":"acc-1","name":"Private","imap_host":"h","imap_port":993,"imap_user":"u","created_at":"2026-11-14T08:00:00Z","updated_at":"2026-11-14T08:00:00Z"},
                |{"id":"acc-2","name":"Work","imap_host":"h","imap_port":993,"imap_user":"u","created_at":"2026-11-14T08:00:00Z","updated_at":"2026-11-14T08:00:00Z"}]
                """.trimMargin(),
            ),
        )
    }

    private fun noContent() =
        mockwebserver3.MockResponse
            .Builder()
            .code(204)
            .build()

    @Test
    fun aWriteFailureLandsInTheSystemTabAndCountsThere() =
        runBlocking {
            serve(alert("2", "outbox_stalled"))
            serveNotifications(notification(7), notification(8, account = "acc-2", action = "send"))
            val store = store()
            store.load()
            val state = store.state.value
            assertEquals(listOf(7, 8), state.notifications.map { it.id })
            assertEquals(3, state.systemCount)
            assertEquals("Quarterly report", state.notifications.first().subject)
            assertTrue(state.notifications.first().stillShownAsApplied)
            assertEquals(
                "true",
                server
                    .requestsTo("/api/notifications")
                    .single()
                    .url
                    .queryParameter("unacknowledged_only"),
            )
            assertEquals("Work", state.accountLabel("acc-2"))
        }

    @Test
    fun aRowNamesNoAccountWhileThePersonHasOnlyOne() =
        runBlocking {
            serve()
            server.script("/api/notifications", TestServer.json("[${notification(7)}]"))
            server.script(
                "/api/accounts",
                TestServer.json(
                    """[{"id":"acc-1","name":"Private","imap_host":"h","imap_port":993,"imap_user":"u","created_at":"2026-11-14T08:00:00Z","updated_at":"2026-11-14T08:00:00Z"}]""",
                ),
            )
            val store = store()
            store.load()
            assertNull(store.state.value.accountLabel("acc-1"))
        }

    @Test
    fun aRevertedWriteIsNoLongerShownAsApplied() =
        runBlocking {
            serve()
            serveNotifications(notification(7, reverted = true))
            val store = store()
            store.load()
            assertTrue(
                !store.state.value.notifications
                    .single()
                    .stillShownAsApplied,
            )
        }

    @Test
    fun aPostImapWithoutTheNotificationCentreLeavesTheSystemTabQuiet() =
        runBlocking {
            serve(alert("2", "outbox_stalled"))
            server.script("/api/notifications", TestServer.jsonError(501, "requires PostIMAP 1.3.0"))
            val store = store()
            store.load()
            assertTrue(
                store.state.value.notifications
                    .isEmpty(),
            )
            assertNull("501 is not a failure to report", store.state.value.errorMessage)
            assertEquals(1, store.state.value.systemCount)
        }

    @Test
    fun aFailedNotificationReadKeepsTheRowsAndSaysSo() =
        runBlocking {
            serve()
            serveNotifications(notification(7))
            val store = store()
            store.load()
            server.script("/api/notifications", TestServer.jsonError(500, "broken"))
            store.load()
            assertEquals(1, store.state.value.notifications.size)
            assertEquals("broken", store.state.value.errorMessage)
        }

    @Test
    fun acknowledgingHitsTheRowsAccountAndAsksTheBellAgain() =
        runBlocking {
            serve()
            serveNotifications(notification(7), notification(8, account = "acc-2"))
            val store = store()
            store.load()
            server.script("/api/accounts/acc-2/notifications/8/ack", noContent())
            server.script("/api/alerts/badge", TestServer.json("""{"count":1}"""))
            store.acknowledge(
                store.state.value.notifications
                    .last(),
            )
            assertEquals(
                listOf(7),
                store.state.value.notifications
                    .map { it.id },
            )
            assertEquals(1, server.requestsTo("/api/accounts/acc-2/notifications/8/ack").size)
            assertEquals(1, store.badge.value)
        }

    @Test
    fun aRefusedAcknowledgementLeavesTheRow() =
        runBlocking {
            serve()
            serveNotifications(notification(7))
            val store = store()
            store.load()
            server.script("/api/accounts/acc-1/notifications/7/ack", TestServer.jsonError(500, "broken"))
            store.acknowledge(
                store.state.value.notifications
                    .single(),
            )
            assertEquals(1, store.state.value.notifications.size)
            assertEquals("broken", store.state.value.errorMessage)
        }

    @Test
    fun dismissAllOnTheSystemTabAcknowledgesEveryAccountWithAFailure() =
        runBlocking {
            serve(alert("2", "outbox_stalled"))
            serveNotifications(notification(7), notification(8), notification(9, account = "acc-2"))
            val store = store()
            store.load()
            server.script("/api/accounts/acc-1/notifications/ack-all", noContent())
            server.script("/api/accounts/acc-2/notifications/ack-all", noContent())
            server.script("/api/alerts/dismiss-all", noContent())
            store.dismissAllSystem()
            assertEquals(1, server.requestsTo("/api/accounts/acc-1/notifications/ack-all").size)
            assertEquals(1, server.requestsTo("/api/accounts/acc-2/notifications/ack-all").size)
            assertEquals(1, server.requestsTo("/api/alerts/dismiss-all").size)
            assertEquals(0, store.state.value.systemCount)
        }

    @Test
    fun aNotificationEventRefreshesTheBell() =
        runBlocking {
            serve(alert("1", "mail", "m1"))
            val store = store()
            val events = MutableSharedFlow<List<LiveEvent>>(extraBufferCapacity = 4)
            store.follow(events)
            delay(100)
            events.emit(listOf(LiveEvent(LiveInvalidation.NotificationsChanged, 0)))
            withTimeout(5_000) { while (server.requestsTo("/api/alerts/badge").isEmpty()) delay(20) }
            assertTrue(
                "the list was never opened, so it is not read",
                server.requestsTo("/api/notifications").isEmpty(),
            )
            serveNotifications(notification(7))
            store.load()
            events.emit(listOf(LiveEvent(LiveInvalidation.NotificationsChanged, 0)))
            withTimeout(5_000) { while (server.requestsTo("/api/notifications").size < 2) delay(20) }
        }
}
