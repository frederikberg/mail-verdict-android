package com.frederikberg.mailverdict.kit.accounts

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
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentitiesStoreTest {
    private val server = TestServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private val store =
        IdentitiesStore(
            ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, OkHttpClient()),
            scope,
            "a1",
        )

    private fun identity(
        id: String,
        address: String,
        name: String?,
        default: Boolean,
    ) = """{"id":"$id","account_id":"a1","address":"$address","display_name":${name?.let { "\"$it\"" }},
        |"is_default":$default,"created_at":"2026-01-01T00:00:00+00:00"}
        """.trimMargin()

    private fun body(request: mockwebserver3.RecordedRequest) =
        Json
            .parseToJsonElement(
                request.body!!.utf8(),
            ).jsonObject

    @Test
    fun loadAsksForThisAccountsIdentitiesOnly() =
        runBlocking {
            server.script("/api/identities", TestServer.json("[${identity("i1", "alex@example.com", "Alex", true)}]"))
            store.load()

            assertEquals(
                listOf("alex@example.com"),
                store.state.value.identities
                    .map { it.address },
            )
            assertEquals(
                "a1",
                server
                    .requestsTo("/api/identities")
                    .single()
                    .url
                    .queryParameter("account_id"),
            )
        }

    @Test
    fun theFirstIdentityBecomesTheDefaultAndALaterOneDoesNot() =
        runBlocking {
            server.script("/api/identities", TestServer.json("[]"))
            store.load()
            server.script(
                "/api/identities",
                TestServer.json(identity("i1", "alex@example.com", null, true), code = 201),
            )
            store.add(" alex@example.com ", "")
            val first = body(server.requestsTo("/api/identities").first { it.method == "POST" })
            assertEquals(JsonPrimitive("alex@example.com"), first["address"])
            assertEquals(JsonPrimitive(true), first["is_default"])
            assertEquals(null, first["display_name"])

            server.script("/api/identities", TestServer.json("[${identity("i1", "alex@example.com", null, true)}]"))
            store.load()
            server.script(
                "/api/identities",
                TestServer.json(identity("i2", "billing@example.com", "Billing", false), code = 201),
            )
            store.add("billing@example.com", "Billing")
            val second = body(server.requestsTo("/api/identities").last { it.method == "POST" })
            assertEquals(JsonPrimitive(false), second["is_default"])
            assertEquals(JsonPrimitive("Billing"), second["display_name"])
        }

    @Test
    fun anAddressWithoutAnAtIsRefusedWithoutAskingTheServer() =
        runBlocking {
            val outcome = store.add("alex.example.com", "")

            assertTrue(outcome is WriteOutcome.Failed)
            assertEquals(0, server.requests.size)
        }

    @Test
    fun makingOneTheDefaultPatchesItAndReadsTheListAgain() =
        runBlocking {
            server.script("/api/identities/i2", TestServer.json(identity("i2", "billing@example.com", null, true)))
            server.script("/api/identities", TestServer.json("[${identity("i2", "billing@example.com", null, true)}]"))
            store.makeDefault("i2")

            val patch = server.requestsTo("/api/identities/i2").single()
            assertEquals("PATCH", patch.method)
            assertEquals(JsonPrimitive(true), body(patch)["is_default"])
            assertTrue(
                store.state.value.identities
                    .single()
                    .isDefault,
            )
        }

    @Test
    fun removingDeletesItAndASeverRefusalIsShownInWords() =
        runBlocking {
            server.script("/api/identities", TestServer.json("[]"))
            server.script("/api/identities/i1", MockResponse.Builder().code(204).build())
            assertEquals(WriteOutcome.Done, store.remove("i1"))

            server.script("/api/identities/i2", TestServer.jsonError(409, "the default cannot be removed"))
            assertEquals(WriteOutcome.Failed("the default cannot be removed", 409), store.remove("i2"))
        }
}
