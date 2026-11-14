package com.frederikberg.mailverdict.kit.stores.mailboxes

import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.fixtures.FixtureInterceptor
import com.frederikberg.mailverdict.kit.fixtures.Fixtures
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxesStoreTest {
    private val server = TestServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val a1 = "a1"
    private val a2 = "a2"

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun api(client: OkHttpClient = OkHttpClient()) =
        ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, client)

    private fun store(
        collapse: CollapseStore = InMemoryCollapseStore(),
        pollMs: Long = 60_000,
    ) = MailboxesStore(api(), scope, collapse, pollMs)

    private fun account(
        id: String,
        name: String,
        state: String = "active",
        emoji: String? = null,
        customOrder: Boolean = false,
    ) = """{"id":"$id","name":"$name","imap_host":"h","imap_port":993,"imap_user":"u","state":"$state",
        |"emoji":${emoji?.let { "\"$it\"" }},"folder_order":${if (customOrder) """["x"]""" else "[]"},
        |"state_error":${if (state == "error") "\"login failed\"" else "null"},
        |"created_at":"2026-01-01T00:00:00+00:00","updated_at":"2026-01-01T00:00:00+00:00"}
        """.trimMargin()

    private fun order(vararg folders: Triple<String, String?, Int>) =
        """{"folders":[${folders.joinToString(",") { (name, role, unread) ->
            """{"folder_id":"f-$name","imap_name":"$name","special_use":${role?.let { "\"$it\"" }},
                "unread_count":$unread,"total_count":${unread + 10}}"""
        }}]}"""

    private fun sync(
        id: String,
        state: String = "active",
        lastFull: Boolean = true,
    ): String {
        val last = if (lastFull) "\"2026-11-01T00:00:00+00:00\"" else "null"
        return """{"account_id":"$id","state":"$state","last_full_sync":$last}"""
    }

    private fun serveTwoAccounts() {
        server.script(
            "/api/accounts",
            TestServer.json("[${account(a1, "Private", emoji = "🏠")},${account(a2, "Work")}]"),
        )
        server.script("/api/unified/folders", TestServer.json("[]"))
        server.script("/api/outbox", TestServer.json("[]"))
        server.script("/api/accounts/$a1/sync-status", TestServer.json(sync(a1)))
        server.script("/api/accounts/$a2/sync-status", TestServer.json(sync(a2)))
        server.script(
            "/api/accounts/$a1/folder-order",
            TestServer.json(order(Triple("Zeta", null, 0), Triple("INBOX", "inbox", 4))),
        )
        server.script("/api/accounts/$a2/folder-order", TestServer.json(order(Triple("INBOX", "inbox", 1))))
    }

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    private fun eventuallyBlocking(condition: () -> Boolean) = runBlocking { eventually(condition) }

    @Test
    fun theFixtureMailboxesRenderInLeadOrderWithTheirCounts() {
        val client = OkHttpClient.Builder().addInterceptor(FixtureInterceptor(Fixtures.routes)).build()
        val store = MailboxesStore(api(client), scope)

        runBlocking { store.load() }

        val state = store.state.value
        assertEquals(listOf("Inboxes"), state.unifiedRows.map { it.name })
        assertEquals(15, state.unifiedRows.single().unreadCount)
        assertEquals(listOf("🏠", "💼"), state.unifiedRows.single().contributingAccountEmojis)
        val privateFolders = state.accountSections.first().folders
        assertEquals(
            listOf("Inbox", "Drafts", "Sent", "Archive", "Trash", "Receipts"),
            privateFolders.map { it.displayName },
        )
        assertEquals(12, privateFolders.first().badgeCount)
        assertEquals(3, privateFolders[1].badgeCount)
        assertFalse(state.isLoading)
        assertEquals(null, state.loadError)
    }

    @Test
    fun aSavedFolderOrderIsFollowedWithTheInboxPromoted() {
        server.script("/api/accounts", TestServer.json("[${account(a1, "Private", customOrder = true)}]"))
        server.script("/api/unified/folders", TestServer.json("[]"))
        server.script("/api/outbox", TestServer.json("[]"))
        server.script("/api/accounts/$a1/sync-status", TestServer.json(sync(a1)))
        server.script(
            "/api/accounts/$a1/folder-order",
            TestServer.json(order(Triple("Zeta", null, 0), Triple("INBOX", "inbox", 4), Triple("Alpha", null, 0))),
        )
        val store = store()

        runBlocking { store.load() }

        assertEquals(
            listOf("Inbox", "Zeta", "Alpha"),
            store.state.value.accountSections.single().folders.map {
                it.displayName
            },
        )
    }

    @Test
    fun anAccountThatNeverConnectedShowsItsErrorAndAsksForNoFolders() {
        server.script("/api/accounts", TestServer.json("[${account(a1, "Old", state = "error")}]"))
        server.script("/api/unified/folders", TestServer.json("[]"))
        server.script("/api/outbox", TestServer.json("[]"))
        server.script("/api/accounts/$a1/sync-status", TestServer.json(sync(a1, "error", lastFull = false)))
        val store = store()

        runBlocking { store.load() }

        val section =
            store.state.value.accountSections
                .single()
        assertEquals(AccountConnectionState.NeverConnected, section.connectionState)
        assertEquals("login failed", section.stateError)
        assertTrue(section.folders.isEmpty())
        assertTrue(server.requestsTo("/api/accounts/$a1/folder-order").isEmpty())
    }

    @Test
    fun anAccountThatSyncedBeforeIsRetryingAndKeepsItsFolders() {
        server.script("/api/accounts", TestServer.json("[${account(a1, "Flaky", state = "error")}]"))
        server.script("/api/unified/folders", TestServer.json("[]"))
        server.script("/api/outbox", TestServer.json("[]"))
        server.script("/api/accounts/$a1/sync-status", TestServer.json(sync(a1, "error", lastFull = true)))
        server.script("/api/accounts/$a1/folder-order", TestServer.json(order(Triple("INBOX", "inbox", 1))))
        val store = store()

        runBlocking { store.load() }

        val section =
            store.state.value.accountSections
                .single()
        assertEquals(AccountConnectionState.Retrying, section.connectionState)
        assertEquals(1, section.folders.size)
    }

    @Test
    fun oneAccountsFolderFailureNeverBlocksAnother() {
        serveTwoAccounts()
        server.script("/api/accounts/$a1/folder-order", TestServer.jsonError(500, "boom"))
        val store = store()

        runBlocking { store.load() }

        val sections = store.state.value.accountSections
        assertTrue(sections[0].folders.isEmpty())
        assertEquals(listOf("Inbox"), sections[1].folders.map { it.displayName })
    }

    @Test
    fun aFailedLoadSaysSoAndKeepsWhatWasOnScreen() {
        serveTwoAccounts()
        val store = store()
        runBlocking { store.load() }
        server.script("/api/accounts", TestServer.jsonError(500, "server_error"))

        runBlocking { store.load() }

        assertEquals("server_error", store.state.value.loadError)
        assertEquals(2, store.state.value.accountSections.size)
        assertFalse(store.state.value.isLoading)
    }

    @Test
    fun aPersonWithNoAccountIsToldApartFromAFailedLoad() {
        server.script("/api/accounts", TestServer.json("[]"))
        server.script("/api/unified/folders", TestServer.json("[]"))
        server.script("/api/outbox", TestServer.json("[]"))
        val store = store()

        runBlocking { store.load() }

        assertFalse(store.state.value.hasAnyAccount)
        assertEquals(null, store.state.value.loadError)
    }

    @Test
    fun deadMailInTheOutboxRaisesTheBanner() {
        serveTwoAccounts()
        val row = """{"id":"o1","account_id":"$a2","kind":"send","status":"dead","created_at":"x","updated_at":"x"}"""
        server.script("/api/outbox", TestServer.json("[$row,$row]"))
        val store = store()

        runBlocking { store.load() }

        assertEquals("2 messages could not be sent — check SMTP settings on Work.", store.state.value.deadOutboxBanner)
    }

    @Test
    fun theOutboxBeingUnreadableNeverBreaksTheOverview() {
        serveTwoAccounts()
        server.script("/api/outbox", TestServer.jsonError(500, "boom"))
        val store = store()

        runBlocking { store.load() }

        assertEquals(2, store.state.value.accountSections.size)
        assertEquals(null, store.state.value.deadOutboxBanner)
    }

    @Test
    fun loadsRunningTogetherCostAtMostOneFollowUp() {
        serveTwoAccounts()
        server.script("/api/accounts", TestServer.json("[${account(a1, "Private")}]", delayMs = 200))
        val store = store()

        runBlocking { (1..5).map { async { store.load() } }.awaitAll() }

        val requests = server.requestsTo("/api/accounts").size
        assertTrue("$requests reads of /accounts", requests in 1..2)
    }

    @Test
    fun aNewMailEventRereadsOnlyItsAccountAndTheUnifiedViews() {
        serveTwoAccounts()
        val events = MutableSharedFlow<List<LiveEvent>>()
        val store = store()
        runBlocking { store.load() }
        store.follow(events, emptyFlow())
        server.script("/api/accounts/$a2/folder-order", TestServer.json(order(Triple("INBOX", "inbox", 9))))
        val before = server.requestsTo("/api/accounts/$a1/folder-order").size

        runBlocking {
            events.subscriptionCount.first { it > 0 }
            events.emit(listOf(LiveEvent(LiveInvalidation.MailNew(a2, "f-INBOX", "m1"), 0)))
            eventually {
                store.state.value.accountSections[1]
                    .folders
                    .first()
                    .badgeCount == 9
            }
        }

        assertEquals(before, server.requestsTo("/api/accounts/$a1/folder-order").size)
        assertEquals(1, server.requestsTo("/api/accounts").size)
    }

    @Test
    fun aFolderNobodyKnowsRereadsEveryAccount() {
        serveTwoAccounts()
        val events = MutableSharedFlow<List<LiveEvent>>()
        val store = store()
        runBlocking { store.load() }
        store.follow(events, emptyFlow())
        server.script("/api/accounts/$a1/folder-order", TestServer.json(order(Triple("INBOX", "inbox", 7))))
        server.script("/api/accounts/$a2/folder-order", TestServer.json(order(Triple("INBOX", "inbox", 8))))

        runBlocking {
            events.subscriptionCount.first { it > 0 }
            events.emit(listOf(LiveEvent(LiveInvalidation.FolderSynced("f-never-seen"), 0)))
            eventually {
                store.state.value.accountSections
                    .map { it.folders.first().badgeCount } == listOf(7, 8)
            }
        }
    }

    @Test
    fun anAddedUnifiedViewForcesAFullReload() {
        serveTwoAccounts()
        val events = MutableSharedFlow<List<LiveEvent>>()
        val store = store()
        runBlocking { store.load() }
        store.follow(events, emptyFlow())
        val view = """{"id":"u1","unified_name":"Inboxes","total_count":5,"unread_count":5,"folders":[]}"""
        server.script("/api/unified/folders", TestServer.json("[$view]"))

        runBlocking {
            events.subscriptionCount.first { it > 0 }
            events.emit(listOf(LiveEvent(LiveInvalidation.MailNew(a1, "f-INBOX", "m"), 0)))
            eventually { store.state.value.unifiedRows.size == 1 }
        }

        assertTrue(server.requestsTo("/api/accounts").size >= 2)
    }

    @Test
    fun theDevicesPathReturningRetriesAFailedLoad() {
        server.script("/api/accounts", TestServer.jsonError(500, "boom"))
        server.script("/api/unified/folders", TestServer.json("[]"))
        server.script("/api/outbox", TestServer.json("[]"))
        val restored = MutableSharedFlow<Unit>()
        val store = store()
        runBlocking { store.load() }
        store.follow(emptyFlow(), restored)
        serveTwoAccounts()

        runBlocking {
            restored.subscriptionCount.first { it > 0 }
            restored.emit(Unit)
            eventually { store.state.value.accountSections.size == 2 }
        }

        assertEquals(null, store.state.value.loadError)
    }

    @Test
    fun theSyncPollMovesAnAccountsStateAndIgnoresAFailedPoll() {
        serveTwoAccounts()
        val store = store(pollMs = 30)
        runBlocking { store.load() }
        store.startSyncStatusPolling()
        try {
            server.script("/api/accounts/$a1/sync-status", TestServer.json(sync(a1, "error", lastFull = true)))
            eventuallyBlocking {
                store.state.value.accountSections[0]
                    .connectionState == AccountConnectionState.Retrying
            }
            server.script("/api/accounts/$a1/sync-status", TestServer.jsonError(500, "boom"))
            val polls = server.requestsTo("/api/accounts/$a1/sync-status").size
            eventuallyBlocking { server.requestsTo("/api/accounts/$a1/sync-status").size > polls + 2 }

            assertEquals(
                AccountConnectionState.Retrying,
                store.state.value.accountSections[0]
                    .connectionState,
            )
        } finally {
            store.stopSyncStatusPolling()
        }
    }

    @Test
    fun collapsingASectionIsRememberedAcrossStores() {
        val collapse = InMemoryCollapseStore()
        val first = store(collapse)
        first.toggleCollapsed(MailboxesSupport.UNIFIED_KEY)
        first.toggleCollapsed(MailboxesSupport.accountKey(a1))
        first.toggleCollapsed(MailboxesSupport.accountKey(a1))

        val second = store(collapse)

        assertTrue(second.state.value.isCollapsed(MailboxesSupport.UNIFIED_KEY))
        assertFalse(second.state.value.isCollapsed(MailboxesSupport.accountKey(a1)))
        assertNotNull(second.state.value.collapsedKeys)
    }
}
