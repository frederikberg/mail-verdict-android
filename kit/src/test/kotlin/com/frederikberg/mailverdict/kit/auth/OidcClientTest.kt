package com.frederikberg.mailverdict.kit.auth

import com.frederikberg.mailverdict.contract.AuthConfigResponse
import com.frederikberg.mailverdict.kit.OidcFixtures
import com.frederikberg.mailverdict.kit.TestServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OidcClientTest {
    private val server = TestServer()

    @After
    fun stop() = server.close()

    private fun client(nowMs: () -> Long = { 0 }) = OidcClient(nowMs = nowMs)

    private fun metadata(): OidcProviderMetadata =
        runBlocking {
            server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server))
            client().discover(server.baseUrl)
        }

    private val config get() = OidcFixtures.config(server.baseUrl)

    // Discovery

    @Test
    fun discoveryReadsTheEndpointsFromTheWellKnownDocument() {
        val found = metadata()
        assertEquals("${server.baseUrl}/oauth/v2/authorize", found.authorizationEndpoint)
        assertEquals("${server.baseUrl}/oauth/v2/token", found.tokenEndpoint)
        assertEquals(1, server.requestsTo(OidcFixtures.DISCOVERY).size)
    }

    @Test
    fun discoveryRefusesADocumentForAnotherIssuer() {
        server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server, issuer = "https://evil.example.net"))
        assertThrows(OidcError.DiscoveryFailed::class.java) { runBlocking { client().discover(server.baseUrl) } }
    }

    @Test
    fun discoveryRefusesEndpointsOnAnotherScheme() {
        val document =
            """{"issuer":"${server.baseUrl}","authorization_endpoint":"https://x.example.com/a","token_endpoint":"https://x.example.com/t"}"""
        server.script(OidcFixtures.DISCOVERY, TestServer.json(document))
        assertThrows(OidcError.DiscoveryFailed::class.java) { runBlocking { client().discover(server.baseUrl) } }
    }

    @Test
    fun discoveryReadsTheRevocationEndpointWhenThereIsOne() {
        server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server, revocable = true))

        assertEquals(
            "${server.baseUrl}${OidcFixtures.REVOKE}",
            runBlocking { client().discover(server.baseUrl) }.revocationEndpoint,
        )
        assertNull(metadata().revocationEndpoint)
    }

    @Test
    fun discoveryRefusesARevocationEndpointOnAnotherScheme() {
        val document =
            """{"issuer":"${server.baseUrl}","authorization_endpoint":"${server.baseUrl}/a","token_endpoint":"${server.baseUrl}/t","revocation_endpoint":"https://x.example.com/r"}"""
        server.script(OidcFixtures.DISCOVERY, TestServer.json(document))
        assertThrows(OidcError.DiscoveryFailed::class.java) { runBlocking { client().discover(server.baseUrl) } }
    }

    @Test
    fun revokingSendsTheRefreshTokenAndTheClientButNoSecret() {
        server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server, revocable = true))
        server.script(OidcFixtures.REVOKE, TestServer.status(200))
        val tokens = OidcFixtures.tokens(server.baseUrl, access = "access-1", refresh = "refresh+1")

        runBlocking { client().let { it.revoke(tokens, it.discover(server.baseUrl)) } }

        val body = OidcFixtures.bodyOf(server.requestsTo(OidcFixtures.REVOKE).single())
        assertEquals("token=refresh%2B1&token_type_hint=refresh_token&client_id=${OidcFixtures.CLIENT_ID}", body)
    }

    @Test
    fun aSetWithNoRefreshTokenRevokesItsAccessToken() {
        server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server, revocable = true))
        server.script(OidcFixtures.REVOKE, TestServer.status(200))
        val tokens = OidcFixtures.tokens(server.baseUrl, access = "access-1", refresh = null)

        runBlocking { client().let { it.revoke(tokens, it.discover(server.baseUrl)) } }

        val body = OidcFixtures.bodyOf(server.requestsTo(OidcFixtures.REVOKE).single())
        assertTrue(body, "token=access-1&token_type_hint=access_token" in body)
    }

    @Test
    fun revokingAnswersNothingWhenTheProviderOffersNoRevocation() {
        val tokens = OidcFixtures.tokens(server.baseUrl)

        runBlocking { client().revoke(tokens, metadata()) }

        assertTrue(server.requestsTo(OidcFixtures.REVOKE).isEmpty())
    }

    @Test
    fun aRevocationTheProviderAnswersWithAnErrorIsUnavailable() {
        server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server, revocable = true))
        server.script(OidcFixtures.REVOKE, TestServer.status(503))
        val tokens = OidcFixtures.tokens(server.baseUrl)

        assertThrows(OidcError.Unavailable::class.java) {
            runBlocking { client().let { it.revoke(tokens, it.discover(server.baseUrl)) } }
        }
    }

    @Test
    fun anOutageDuringDiscoveryIsUnavailableNotARejection() {
        server.script(OidcFixtures.DISCOVERY, TestServer.status(503))
        val error =
            assertThrows(OidcError.Unavailable::class.java) { runBlocking { client().discover(server.baseUrl) } }
        assertFalse(error.isRejection)
    }

    // Authorization request and callback

    @Test
    fun theAuthorizationUrlCarriesTheCodeFlowParameters() {
        val url =
            client()
                .authorizationUrl(config, metadata(), Pkce.Codes("v", "chal"), "st")
                .toHttpUrl()
        assertEquals("/oauth/v2/authorize", url.encodedPath)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals(OidcFixtures.CLIENT_ID, url.queryParameter("client_id"))
        assertEquals("mailverdict://auth/callback", url.queryParameter("redirect_uri"))
        assertEquals("openid profile email offline_access", url.queryParameter("scope"))
        assertEquals("st", url.queryParameter("state"))
        assertEquals("chal", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
    }

    @Test
    fun aPlusInAnAuthorizationParameterIsEscaped() {
        val url = client().authorizationUrl(config.copy(scopes = "a+b"), metadata(), Pkce.Codes("v", "c"), "s")
        assertTrue(url, "scope=a%2Bb" in url)
    }

    @Test
    fun theAuthorizationUrlNeedsAClientId() {
        val noClient = AuthConfigResponse(issuer = server.baseUrl, clientId = null, scopes = "", redirectUri = "")
        assertThrows(OidcError.SignInUnavailable::class.java) {
            client().authorizationUrl(noClient, metadata(), Pkce.makePair(), "s")
        }
    }

    @Test
    fun aCallbackYieldsTheCodeOnlyForTheExpectedState() {
        val client = client()
        assertEquals("abc+1", client.parseCallback("mailverdict://auth/callback?code=abc%2B1&state=st", "st"))

        assertThrows(OidcError.CallbackRejected::class.java) {
            client.parseCallback("mailverdict://auth/callback?code=abc&state=other", "st")
        }
        assertThrows(OidcError.CallbackRejected::class.java) {
            client.parseCallback("mailverdict://auth/callback?state=st", "st")
        }
        assertThrows(OidcError.CallbackRejected::class.java) {
            client.parseCallback("mailverdict://auth/callback?code=&state=st", "st")
        }
        val denied =
            assertThrows(OidcError.CallbackRejected::class.java) {
                client.parseCallback("mailverdict://auth/callback?error=access_denied&state=st", "st")
            }
        assertEquals("access_denied", denied.message)
    }

    // Token requests

    @Test
    fun theCodeExchangeSendsTheVerifierAndBuildsTheTokenSet() {
        val found = metadata()
        server.script(OidcFixtures.TOKEN, OidcFixtures.tokenAnswer("a", "r", expiresIn = 600))
        val tokens =
            runBlocking {
                client(
                    nowMs = { 1_000_000 },
                ).exchangeCode("the code", Pkce.Codes("ver+ifier", "c"), config, found)
            }

        assertEquals("a", tokens.accessToken)
        assertEquals("r", tokens.refreshToken)
        assertEquals(1_000_000 + 600_000L, tokens.expiresAtMs)
        assertEquals(server.baseUrl, tokens.issuer)
        assertEquals(OidcFixtures.CLIENT_ID, tokens.clientId)

        val request = server.requestsTo(OidcFixtures.TOKEN).single()
        assertEquals("POST", request.method)
        val body = OidcFixtures.bodyOf(request)
        assertTrue(body, "grant_type=authorization_code" in body)
        assertTrue(body, "code=the%20code" in body)
        assertTrue(body, "code_verifier=ver%2Bifier" in body)
        assertTrue(body, "redirect_uri=mailverdict%3A%2F%2Fauth%2Fcallback" in body)
        assertNull("a public client sends no secret", request.headers["Authorization"])
    }

    @Test
    fun aRefreshReplacesARotatedRefreshToken() {
        val found = metadata()
        server.script(OidcFixtures.TOKEN, OidcFixtures.tokenAnswer("a2", "r2"))
        val renewed = runBlocking { client().refresh(OidcFixtures.tokens(server.baseUrl), found) }
        assertEquals("a2", renewed.accessToken)
        assertEquals("r2", renewed.refreshToken)
        val body = OidcFixtures.bodyOf(server.requestsTo(OidcFixtures.TOKEN).single())
        assertTrue(body, "grant_type=refresh_token" in body)
        assertTrue(body, "refresh_token=refresh-1" in body)
    }

    @Test
    fun aRefreshKeepsTheOldRefreshTokenWhenNoneComesBack() {
        val found = metadata()
        server.script(OidcFixtures.TOKEN, OidcFixtures.tokenAnswer("a2", null))
        val renewed = runBlocking { client().refresh(OidcFixtures.tokens(server.baseUrl), found) }
        assertEquals("refresh-1", renewed.refreshToken)
    }

    @Test
    fun aGrantRefusalIsARejectionAndAnOutageIsNot() {
        val found = metadata()
        for (code in listOf(400, 401)) {
            server.script(OidcFixtures.TOKEN, OidcFixtures.tokenRefusal(code))
            val error =
                assertThrows(
                    OidcError::class.java,
                ) { runBlocking { client().refresh(OidcFixtures.tokens(server.baseUrl), found) } }
            assertTrue("HTTP $code", error.isRejection)
        }
        for (code in listOf(429, 500, 503)) {
            server.script(OidcFixtures.TOKEN, TestServer.status(code))
            val error =
                assertThrows(
                    OidcError::class.java,
                ) { runBlocking { client().refresh(OidcFixtures.tokens(server.baseUrl), found) } }
            assertFalse("HTTP $code", error.isRejection)
        }
    }

    @Test
    fun aRefreshWithoutARefreshTokenIsRejectedWithoutAskingTheProvider() {
        val found = metadata()
        val error =
            assertThrows(OidcError::class.java) {
                runBlocking { client().refresh(OidcFixtures.tokens(server.baseUrl, refresh = null), found) }
            }
        assertTrue(error.isRejection)
        assertTrue(server.requestsTo(OidcFixtures.TOKEN).isEmpty())
    }

    @Test
    fun formEncodingEscapesEverythingButUnreservedCharacters() {
        assertEquals(
            "a%20b=c%2Bd%3De%26f&k=A-._~z",
            OidcClient.formEncoded(listOf("a b" to "c+d=e&f", "k" to "A-._~z")),
        )
    }
}
