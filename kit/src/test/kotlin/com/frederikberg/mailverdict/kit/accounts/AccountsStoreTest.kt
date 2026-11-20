package com.frederikberg.mailverdict.kit.accounts

import com.frederikberg.mailverdict.contract.AccountCreateRequest
import com.frederikberg.mailverdict.contract.AccountUpdateRequest
import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import com.frederikberg.mailverdict.kit.stores.mailboxes.AccountConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsStoreTest {
    private val server = TestServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private val store =
        AccountsStore(
            ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, okhttp3.OkHttpClient()),
            scope,
        )

    private fun account(
        id: String,
        name: String,
        state: String = "active",
        emoji: String? = null,
        spam: Boolean = false,
    ) = """{"id":"$id","name":"$name","imap_host":"imap.example.com","imap_port":993,"imap_user":"$name@example.com",
        |"state":"$state","emoji":${emoji?.let { "\"$it\"" }},"spam_enabled":$spam,
        |"created_at":"2026-01-01T00:00:00+00:00","updated_at":"2026-01-01T00:00:00+00:00"}
        """.trimMargin()

    private fun sync(id: String) =
        """{"account_id":"$id","state":"active","last_incr_sync":"2026-11-13T07:00:00+00:00"}"""

    private fun serveTwoAccounts() {
        server.script(
            "/api/accounts",
            TestServer.json("[${account("a1", "Private", emoji = "🏠")},${account("a2", "Work", state = "error")}]"),
        )
        server.script("/api/accounts/a1/sync-status", TestServer.json(sync("a1")))
        server.script("/api/accounts/a2/sync-status", TestServer.json("""{"account_id":"a2","state":"error"}"""))
    }

    private fun noContent() = MockResponse.Builder().code(204).build()

    @Test
    fun loadListsEveryAccountWithItsSyncStatusAndConnectionState() =
        runBlocking {
            serveTwoAccounts()
            store.load()

            val rows = store.state.value.rows
            assertEquals(listOf("Private", "Work"), rows.map { it.account.name })
            assertEquals("2026-11-13T07:00:00+00:00", rows[0].lastSync)
            assertEquals(AccountConnectionState.NeverConnected, rows[1].connection)
            assertTrue(store.state.value.hasLoaded)
        }

    @Test
    fun anAccountWhoseSyncStatusIsUnreadableStillAppears() =
        runBlocking {
            server.script("/api/accounts", TestServer.json("[${account("a1", "Private")}]"))
            server.script("/api/accounts/a1/sync-status", TestServer.jsonError(500, "boom"))
            store.load()

            assertEquals(1, store.state.value.rows.size)
            assertNull(
                store.state.value.rows[0]
                    .sync,
            )
        }

    @Test
    fun aFailedLoadKeepsWhatWasShownAndSaysWhy() =
        runBlocking {
            serveTwoAccounts()
            store.load()
            server.script("/api/accounts", TestServer.jsonError(500, "database is down"))
            store.load()

            assertEquals(2, store.state.value.rows.size)
            assertEquals("database is down", store.state.value.errorMessage)
        }

    @Test
    fun creatingPostsTheRequestAndThenReadsTheListAgain() =
        runBlocking {
            serveTwoAccounts()
            store.load()
            server.script("/api/accounts", TestServer.json(account("a3", "New"), code = 201))
            val outcome =
                store.create(AccountCreateRequest(name = "New", imapHost = "h", imapUser = "u", imapPassword = "pw"))

            assertEquals(WriteOutcome.Done, outcome)
            val post = server.requestsTo("/api/accounts").first { it.method == "POST" }
            val sent = Json.parseToJsonElement(post.body!!.utf8()).jsonObject
            assertEquals(JsonPrimitive("New"), sent["name"])
            assertEquals(JsonPrimitive(993), sent["imap_port"])
            assertTrue("the list was read again", server.requestsTo("/api/accounts").count { it.method == "GET" } >= 2)
        }

    @Test
    fun aRefusalFromTheServerComesBackInWordsAndChangesNothing() =
        runBlocking {
            serveTwoAccounts()
            store.load()
            server.script("/api/accounts", TestServer.jsonError(422, "imap_host must not be empty"))
            val outcome = store.create(AccountCreateRequest(name = "New", imapHost = "", imapUser = "u"))

            assertEquals(WriteOutcome.Failed("imap_host must not be empty", 422), outcome)
            assertEquals(2, store.state.value.rows.size)
        }

    @Test
    fun updatingShowsTheServersAnswerAndSendsTheExplicitNullThatClearsARetention() =
        runBlocking {
            serveTwoAccounts()
            store.load()
            server.script("/api/accounts/a1", TestServer.json(account("a1", "Renamed", spam = true)))
            val outcome =
                store.update(
                    "a1",
                    AccountUpdateRequest(
                        name = "Renamed",
                        spamEnabled = true,
                        trashRetentionDays = AccountUpdateRequest.days(null),
                    ),
                )

            assertEquals(WriteOutcome.Done, outcome)
            assertEquals(
                "Renamed",
                store.state.value
                    .row("a1")
                    ?.account
                    ?.name,
            )
            val patch = server.requestsTo("/api/accounts/a1").single { it.method == "PATCH" }
            val sent = Json.parseToJsonElement(patch.body!!.utf8()).jsonObject
            assertEquals(JsonNull, sent["trash_retention_days"])
            assertEquals(JsonPrimitive(true), sent["spam_enabled"])
        }

    @Test
    fun deletingRemovesTheRowOnlyOnceTheServerAgreed() =
        runBlocking {
            serveTwoAccounts()
            store.load()
            server.script("/api/accounts/a2", TestServer.jsonError(409, "still syncing"))
            assertEquals(WriteOutcome.Failed("still syncing", 409), store.delete("a2"))
            assertEquals(2, store.state.value.rows.size)

            server.script("/api/accounts/a2", noContent())
            assertEquals(WriteOutcome.Done, store.delete("a2"))
            assertEquals(
                listOf("a1"),
                store.state.value.rows
                    .map { it.id },
            )
        }

    @Test
    fun syncingNowAsksTheServerAndReadsTheStatusAgain() =
        runBlocking {
            serveTwoAccounts()
            store.load()
            server.script("/api/accounts/a1/sync", TestServer.json("""{"status":"queued"}"""))
            server.script(
                "/api/accounts/a1/sync-status",
                TestServer.json(
                    """{"account_id":"a1","state":"active","last_incr_sync":"2026-11-20T09:00:00+00:00"}""",
                ),
            )
            store.syncNow("a1")

            assertEquals(
                "2026-11-20T09:00:00+00:00",
                store.state.value
                    .row("a1")
                    ?.lastSync,
            )
        }

    @Test
    fun theEmojiIsSetOnTheServerAndThenShown() =
        runBlocking {
            serveTwoAccounts()
            store.load()
            server.script("/api/accounts/a2/emoji", TestServer.json("""{"emoji":"💼"}"""))
            store.setEmoji("a2", "💼")

            assertEquals(
                "💼",
                store.state.value
                    .row("a2")
                    ?.account
                    ?.emoji,
            )
            val put = server.requestsTo("/api/accounts/a2/emoji").single()
            assertEquals(JsonPrimitive("💼"), Json.parseToJsonElement(put.body!!.utf8()).jsonObject["emoji"])
        }
}
