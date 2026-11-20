package com.frederikberg.mailverdict.kit.settings

import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSettingsStoreTest {
    private val server = TestServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private val store =
        UserSettingsStore(
            ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, OkHttpClient()),
            scope,
        )

    private val mail =
        """{"mark_read_on_file_to_archive_or_junk":true,"show_folder_totals":false,"bell_badge_counts_new_mail":true,
        |"notify_wait_seconds":30,"account_order":["a"],"future_flag":true,"admin_only_key":7}
        """.trimMargin()

    private val schema =
        """{"mail":{"mark_read_on_file_to_archive_or_junk":{"scope":"user","type":"boolean"},
        |"future_flag":{"scope":"user","type":"boolean"},"account_order":{"scope":"user","type":"array"},
        |"admin_only_key":{"scope":"admin","type":"number"}},
        |"outbox":{"undo_send_seconds":{"scope":"user","type":"number"}},
        |"orders":{"language":{"scope":"user","type":"string"},"filter":{"scope":"user","type":"object"},
        |"auto_close_days":{"scope":"user","type":"number"}}}
        """.trimMargin()

    private fun serve() {
        server.script("/api/settings/mail", TestServer.json(mail))
        server.script("/api/settings/outbox", TestServer.json("""{"undo_send_seconds":10}"""))
        server.script(
            "/api/settings/orders",
            TestServer.json("""{"language":"en","filter":{"include":[]},"auto_close_days":14}"""),
        )
        server.script("/api/settings/schema", TestServer.json(schema))
    }

    @Test
    fun loadReadsEveryCategoryAndTheSchema() =
        runBlocking {
            serve()
            store.load()

            assertEquals(setOf("mail", "outbox", "orders"), store.state.value.values.keys)
            assertEquals(
                "user",
                store.state.value.schema["outbox"]
                    ?.get("undo_send_seconds")
                    ?.scope,
            )
        }

    @Test
    fun sectionsShowNamedFieldsThenPlainOnesTheServerTagsAsTheUsersOwn() {
        runBlocking {
            serve()
            store.load()
        }
        val sections = UserSettingsStore.sections(store.state.value).associateBy { it.title }

        assertEquals(
            listOf("mark_read_on_file_to_archive_or_junk", "show_folder_totals", "future_flag"),
            sections.getValue("Reading and filing").fields.map { it.spec.key },
        )
        assertEquals(
            "Future flag",
            sections
                .getValue("Reading and filing")
                .fields
                .last()
                .spec.label,
        )
        assertEquals(
            listOf("bell_badge_counts_new_mail", "notify_wait_seconds"),
            sections.getValue("Notifications").fields.map { it.spec.key },
        )
        assertEquals(listOf("undo_send_seconds"), sections.getValue("Sending").fields.map { it.spec.key })
        assertEquals(
            listOf("language", "auto_close_days"),
            sections.getValue("Orders & tickets").fields.map { it.spec.key },
        )
    }

    @Test
    fun anAdminOnlyKeyAndKeysOtherScreensWriteAreNeverShown() {
        runBlocking {
            serve()
            store.load()
        }
        val keys = UserSettingsStore.sections(store.state.value).flatMap { s -> s.fields.map { it.spec.key } }

        assertTrue("admin_only_key" !in keys)
        assertTrue("account_order" !in keys)
        assertTrue("filter" !in keys)
    }

    @Test
    fun aCategoryTheServerRefusesLeavesTheOthersShown() =
        runBlocking {
            serve()
            server.script("/api/settings/orders", TestServer.jsonError(403, "admin only"))
            store.load()

            assertEquals(setOf("mail", "outbox"), store.state.value.values.keys)
            assertNull(store.state.value.errorMessage)
        }

    @Test
    fun whenNothingCanBeReadTheServersWordsAreTheError() =
        runBlocking {
            server.script("/api/settings/mail", TestServer.jsonError(500, "database is down"))
            server.script("/api/settings/outbox", TestServer.jsonError(500, "database is down"))
            server.script("/api/settings/orders", TestServer.jsonError(500, "database is down"))
            store.load()

            assertEquals("database is down", store.state.value.errorMessage)
            assertEquals(false, store.state.value.hasLoaded)
        }

    @Test
    fun aWriteSendsOnlyTheChangedKeyInsideData() =
        runBlocking {
            serve()
            store.load()
            server.script("/api/settings/outbox", TestServer.json("""{"undo_send_seconds":20}"""))
            val outcome = store.set("outbox", "undo_send_seconds", JsonPrimitive(20))

            assertEquals(WriteOutcome.Done, outcome)
            val put = server.requestsTo("/api/settings/outbox").single { it.method == "PUT" }
            assertEquals("""{"data":{"undo_send_seconds":20}}""", put.body!!.utf8())
            assertEquals(
                JsonPrimitive(20),
                store.state.value.values
                    .getValue("outbox")["undo_send_seconds"],
            )
        }

    @Test
    fun aRefusedWritePutsTheOldValueBack() =
        runBlocking {
            serve()
            store.load()
            server.script("/api/settings/outbox", TestServer.jsonError(422, "undo_send_seconds must be 0 to 30"))
            val outcome = store.set("outbox", "undo_send_seconds", JsonPrimitive(99))

            assertEquals(WriteOutcome.Failed("undo_send_seconds must be 0 to 30", 422), outcome)
            assertEquals(
                JsonPrimitive(10),
                store.state.value.values
                    .getValue("outbox")["undo_send_seconds"],
            )
            assertEquals(
                JsonPrimitive(99),
                Json
                    .parseToJsonElement(
                        server
                            .requestsTo("/api/settings/outbox")
                            .last { it.method == "PUT" }
                            .body!!
                            .utf8(),
                    ).jsonObject["data"]!!
                    .jsonObject["undo_send_seconds"],
            )
        }
}
