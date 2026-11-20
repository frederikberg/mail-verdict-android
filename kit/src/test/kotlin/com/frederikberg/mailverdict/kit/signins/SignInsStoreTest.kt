package com.frederikberg.mailverdict.kit.signins

import com.frederikberg.mailverdict.contract.SignInKind
import com.frederikberg.mailverdict.contract.SignInResponse
import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInsStoreTest {
    private val server = TestServer()
    private val signedOut = mutableListOf<String>()

    @After
    fun stop() = server.close()

    private val store =
        SignInsStore(
            ApiClient(RequestFactory(server.baseUrl, "Test phone") { AuthMode.None }, OkHttpClient()),
        ) { signedOut += "this device" }

    private fun signIn(
        id: String,
        kind: String,
        name: String?,
        current: Boolean = false,
        revoked: Boolean = false,
    ) = """{"id":"$id","user_id":"u1","kind":"$kind","name":${name?.let { "\"$it\"" }},"subject":"sub-$id",
        |"first_seen_at":"2026-11-01T08:00:00+00:00","last_seen_at":"2026-11-19T18:30:00+00:00",
        |"revoked_at":${if (revoked) "\"2026-11-19T19:00:00+00:00\"" else "null"},"current":$current}
        """.trimMargin()

    private fun serve(vararg rows: String) =
        server.script(
            "/api/me/sign-ins",
            TestServer.json("[${rows.joinToString(",")}]"),
        )

    @Test
    fun loadKeepsTheServersOrderAndMarksTheCurrentOne() =
        runBlocking {
            serve(
                signIn("s1", "phone", "Pixel 8", current = true),
                signIn("s2", "browser", null),
                signIn("s3", "phone", "Old", revoked = true),
            )
            store.load()

            val rows = store.state.value.signIns
            assertEquals(listOf("s1", "s2", "s3"), rows.map { it.id })
            assertTrue(rows[0].current)
            assertTrue(rows[2].isRevoked)
        }

    @Test
    fun revokingAnotherSignInPostsItsRevokeAndReadsTheListAgain() =
        runBlocking {
            serve(signIn("s1", "phone", "Pixel 8", current = true), signIn("s2", "phone", "Tablet"))
            store.load()
            server.script(
                "/api/me/sign-ins/s2/revoke",
                TestServer.json(signIn("s2", "phone", "Tablet", revoked = true)),
            )
            serve(signIn("s1", "phone", "Pixel 8", current = true), signIn("s2", "phone", "Tablet", revoked = true))
            val outcome = store.revoke(store.state.value.signIns[1])

            assertEquals(WriteOutcome.Done, outcome)
            assertEquals("POST", server.requestsTo("/api/me/sign-ins/s2/revoke").single().method)
            assertTrue(
                store.state.value.signIns[1]
                    .isRevoked,
            )
            assertTrue(signedOut.isEmpty())
        }

    @Test
    fun revokingTheCurrentSignInIsThePhonesOwnSignOutAndSendsNoRevokeOfItsOwn() =
        runBlocking {
            serve(signIn("s1", "phone", "Pixel 8", current = true))
            store.load()
            val outcome =
                store.revoke(
                    store.state.value.signIns
                        .single(),
                )

            assertEquals(WriteOutcome.Done, outcome)
            assertEquals(listOf("this device"), signedOut)
            assertEquals(
                emptyList<String>(),
                server.requests.map { it.url.encodedPath }.filter { it.endsWith("/revoke") },
            )
        }

    @Test
    fun aRefusedRevokeSaysWhyAndKeepsTheRowLive() =
        runBlocking {
            serve(signIn("s2", "phone", "Tablet"))
            store.load()
            server.script("/api/me/sign-ins/s2/revoke", TestServer.jsonError(403, "not yours"))
            val outcome =
                store.revoke(
                    store.state.value.signIns
                        .single(),
                )

            assertEquals(WriteOutcome.Failed("not yours", 403), outcome)
            assertFalse(
                store.state.value.signIns
                    .single()
                    .isRevoked,
            )
            assertTrue(
                store.state.value.revoking
                    .isEmpty(),
            )
        }

    @Test
    fun aFailedLoadKeepsTheRowsAndSaysWhy() =
        runBlocking {
            serve(signIn("s1", "phone", "Pixel 8", current = true))
            store.load()
            server.script("/api/me/sign-ins", TestServer.jsonError(500, "database is down"))
            store.load()

            assertEquals(1, store.state.value.signIns.size)
            assertEquals("database is down", store.state.value.errorMessage)
        }

    private fun response(
        kind: SignInKind,
        name: String?,
        current: Boolean = false,
    ) = SignInResponse(
        "id",
        "u",
        kind,
        name,
        "machine-subject",
        "2026-01-01T00:00:00Z",
        "2026-01-02T00:00:00Z",
        null,
        current,
    )

    @Test
    fun labelsFollowTheWebsWords() {
        assertEquals("Web browsers", SignInDisplay.label(response(SignInKind.Browser, "ignored")))
        assertEquals("Pixel 8", SignInDisplay.label(response(SignInKind.Phone, "Pixel 8")))
        assertEquals("Phone", SignInDisplay.label(response(SignInKind.Phone, null)))
        assertEquals("machine-subject", SignInDisplay.label(response(SignInKind.Machine, null)))
        assertEquals("Phone", SignInDisplay.label(response(SignInKind.Phone, " ")))
    }

    @Test
    fun theConsequenceSaysWhatHappensAndAddsThatTheCurrentOneGoesAtOnce() {
        val other = SignInDisplay.consequence(response(SignInKind.Phone, "Tablet"))
        val current = SignInDisplay.consequence(response(SignInKind.Phone, "Tablet", current = true))

        assertEquals("This phone is signed out. Signing in again on it starts a new sign-in.", other)
        assertEquals("$other That includes this one, right away.", current)
        assertTrue(
            SignInDisplay.consequence(response(SignInKind.Machine, null)).startsWith("This access key stops working"),
        )
    }
}
