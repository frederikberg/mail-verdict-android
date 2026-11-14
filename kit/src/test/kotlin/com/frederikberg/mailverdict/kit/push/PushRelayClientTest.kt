package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.kit.TestServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRelayClientTest {
    private val server = TestServer()
    private val client = PushRelayClient(OkHttpClient())

    @After
    fun stop() = server.close()

    private fun register() = runBlocking { client.register(server.baseUrl + "/", "mailverdict", "fcm", "tok:en_1-2") }

    @Test
    fun theRegistrationNamesTheAppThePlatformAndTheToken() {
        server.script(
            "/v1/register",
            TestServer.json(
                """{"ticket":"sealed","ticket_id":"0123456789abcdef","expires_at":"2027-02-01T10:00:00Z"}""",
            ),
        )

        val ticket = register()

        val body =
            Json
                .parseToJsonElement(
                    server
                        .requestsTo("/v1/register")
                        .single()
                        .body!!
                        .utf8(),
                ).jsonObject
        assertEquals(
            mapOf("app" to "mailverdict", "platform" to "fcm", "token" to "tok:en_1-2"),
            body.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals("sealed", ticket.ticket)
        assertEquals(1_801_476_000_000L, ticket.expiresAtMs)
    }

    @Test
    fun aRefusalIsPassedOnInWords() {
        server.script(
            "/v1/register",
            mockwebserver3.MockResponse
                .Builder()
                .code(400)
                .body("platform fcm is not available for this app")
                .build(),
        )

        val error = assertThrows(PushRelayException::class.java) { register() }

        assertTrue(error.message, "platform fcm is not available" in error.message.orEmpty())
    }

    @Test
    fun aBusyRelayAndAnOutageAreToldApart() {
        server.script("/v1/register", TestServer.status(429))
        assertTrue(assertThrows(PushRelayException::class.java) { register() }.message!!.contains("busy"))

        server.script("/v1/register", TestServer.status(502))
        assertTrue(assertThrows(PushRelayException::class.java) { register() }.message!!.contains("502"))
    }

    @Test
    fun anUnreachableRelayIsReported() {
        val address = server.baseUrl
        server.close()

        val error =
            assertThrows(PushRelayException::class.java) {
                runBlocking { client.register(address, "mailverdict", "fcm", "tok") }
            }

        assertEquals("The push relay could not be reached.", error.message)
    }

    @Test
    fun anAnswerThatIsNotATicketIsReported() {
        server.script("/v1/register", TestServer.json("""{"unexpected":true}"""))

        assertEquals(
            "The push relay's answer could not be read.",
            assertThrows(PushRelayException::class.java) {
                register()
            }.message,
        )
    }
}
