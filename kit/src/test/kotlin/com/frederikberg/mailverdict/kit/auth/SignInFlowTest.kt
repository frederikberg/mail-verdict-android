package com.frederikberg.mailverdict.kit.auth

import com.frederikberg.mailverdict.kit.OidcFixtures
import com.frederikberg.mailverdict.kit.TestServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInFlowTest {
    private val server = TestServer()

    @After
    fun stop() = server.close()

    private fun flow() =
        SignInFlow(
            OidcClient(nowMs = {
                0
            }),
            newState = { "state-1" },
            newPkce = { Pkce.Codes("verifier-1", "challenge-1") },
        )

    private fun provider() {
        server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server))
        server.script(OidcFixtures.TOKEN, OidcFixtures.tokenAnswer("a", "r"))
    }

    @Test
    fun aSignInRunsFromTheAuthorizationUrlToATokenSet() =
        runBlocking {
            provider()
            val pending = flow().begin(OidcFixtures.config(server.baseUrl))
            val url = pending.authorizationUrl.toHttpUrl()
            assertEquals("state-1", url.queryParameter("state"))
            assertEquals("challenge-1", url.queryParameter("code_challenge"))

            val tokens = flow().complete(pending, "mailverdict://auth/callback?code=the-code&state=state-1")
            assertEquals("a", tokens.accessToken)
            val body = OidcFixtures.bodyOf(server.requestsTo(OidcFixtures.TOKEN).single())
            assertTrue(body, "code=the-code" in body)
            assertTrue(body, "code_verifier=verifier-1" in body)
        }

    /** An answer that does not belong to this attempt must be refused before the code is ever spent. */
    @Test
    fun aCallbackForAnotherAttemptNeverReachesTheTokenEndpoint() {
        runBlocking {
            provider()
            val pending = flow().begin(OidcFixtures.config(server.baseUrl))
            assertThrows(OidcError.CallbackRejected::class.java) {
                runBlocking { flow().complete(pending, "mailverdict://auth/callback?code=stolen&state=other") }
            }
            assertTrue(server.requestsTo(OidcFixtures.TOKEN).isEmpty())
        }
    }

    @Test
    fun aServerWithoutNativeSignInSaysSoBeforeAnythingIsAsked() {
        assertThrows(OidcError.SignInUnavailable::class.java) {
            runBlocking { flow().begin(OidcFixtures.config(server.baseUrl).copy(clientId = null)) }
        }
        assertTrue(server.requests.isEmpty())
    }
}
