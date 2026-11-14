package com.frederikberg.mailverdict.kit.net

import com.frederikberg.mailverdict.kit.OidcFixtures
import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.api.getMe
import com.frederikberg.mailverdict.kit.auth.CredentialRefreshing
import com.frederikberg.mailverdict.kit.auth.OidcClient
import com.frederikberg.mailverdict.kit.auth.OidcTokenHolder
import com.frederikberg.mailverdict.kit.auth.RefreshOutcome
import com.frederikberg.mailverdict.kit.auth.TokenRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The status rules of a signed-in session, driven through the real client, refresher and provider
 * client over one server: which answers renew, which sign out, which do neither.
 */
class ApiClientAuthTest {
    private class CountingRefresher : CredentialRefreshing {
        var refreshes = 0

        override suspend fun refreshIfNeeded() = Unit

        override suspend fun refresh(afterRejectionOf: String?): RefreshOutcome {
            refreshes++
            return RefreshOutcome.Refreshed
        }
    }

    private val server = TestServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private class Rig(
        val client: ApiClient,
        val holder: OidcTokenHolder,
        val signedOut: MutableList<ApiError>,
    )

    private fun rig(
        tokens: com.frederikberg.mailverdict.kit.auth.OidcTokens =
            OidcFixtures.tokens(
                server.baseUrl,
            ),
    ): Rig {
        val holder = OidcTokenHolder(tokens)
        val signedOut = CopyOnWriteArrayList<ApiError>()
        val refresher = TokenRefresher(scope, holder, OidcClient(), persist = {})
        val client =
            ApiClient(
                requests = RequestFactory(server.baseUrl, "Test phone") { holder.authMode },
                httpClient = OkHttpClient(),
                onAuthenticationFailure = { signedOut += it },
                credentialRefresher = refresher,
            )
        server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server))
        return Rig(client, holder, signedOut)
    }

    private fun bearers(): List<String?> = server.requestsTo("/api/me").map { it.headers["Authorization"] }

    @Test
    fun theBearerIsTheAccessToken() =
        runBlocking {
            val rig = rig()
            server.script("/api/me", TestServer.json(OidcFixtures.ME))
            assertTrue(rig.client.getMe().isAdmin)
            assertEquals(listOf<String?>("Bearer access-1"), bearers())
        }

    @Test
    fun a401RenewsAndRetriesOnceWithTheNewToken() =
        runBlocking {
            val rig = rig()
            server.script("/api/me", TestServer.jsonError(401, "not_authenticated"), TestServer.json(OidcFixtures.ME))
            server.script(OidcFixtures.TOKEN, OidcFixtures.tokenAnswer("access-2", "refresh-2"))
            rig.client.getMe()
            assertEquals(listOf<String?>("Bearer access-1", "Bearer access-2"), bearers())
            assertTrue(rig.signedOut.isEmpty())
        }

    /** The proxy answers an invalid token with a bare 403, which must read as a rejected credential: renew once, and if the new token is refused too, sign out. */
    @Test
    fun theProxys403RenewsOnceThenSignsOut() =
        runBlocking {
            val rig = rig()
            server.script("/api/me", TestServer.proxyForbidden())
            server.script(OidcFixtures.TOKEN, OidcFixtures.tokenAnswer("access-2", "refresh-2"))
            val error = assertThrows(ApiError::class.java) { runBlocking { rig.client.getMe() } }
            assertTrue(error.isAuthenticationFailure)
            assertEquals(listOf<String?>("Bearer access-1", "Bearer access-2"), bearers())
            assertEquals(1, rig.signedOut.size)
        }

    @Test
    fun aRefusedRefreshTokenSignsOutAfterOneTokenRequest() =
        runBlocking {
            val rig = rig()
            server.script("/api/me", TestServer.jsonError(401, "not_authenticated"))
            server.script(OidcFixtures.TOKEN, OidcFixtures.tokenRefusal())
            val error = assertThrows(ApiError::class.java) { runBlocking { rig.client.getMe() } }
            assertTrue(error.isAuthenticationFailure)
            assertEquals(1, bearers().size)
            assertEquals(1, rig.signedOut.size)
        }

    /** An unreachable provider says nothing about the credential: the person stays signed in and sees a connection error, not a sign-in screen. */
    @Test
    fun anUnreachableProviderDoesNotSignOut() =
        runBlocking {
            val rig = rig()
            server.script("/api/me", TestServer.jsonError(401, "not_authenticated"))
            server.script(OidcFixtures.TOKEN, TestServer.status(503))
            val error = assertThrows(ApiError::class.java) { runBlocking { rig.client.getMe() } }
            assertTrue(error is ApiError.Transport)
            assertTrue(rig.signedOut.isEmpty())
            assertEquals("access-1", rig.holder.tokens.accessToken)
        }

    /** The app's own 403s carry a JSON detail: the token is fine, so nothing renews and nothing signs out. */
    @Test
    fun theAppsOwn403LeavesThePersonSignedIn() =
        runBlocking {
            val rig = rig()
            server.script("/api/me", TestServer.jsonError(403, "admin_required"))
            val error = assertThrows(ApiError::class.java) { runBlocking { rig.client.getMe() } }
            assertTrue(error.isForbidden)
            assertFalse(error.endsSession)
            assertEquals(1, server.requests.size)
            assertTrue(rig.signedOut.isEmpty())
        }

    @Test
    fun aDisabledUserIsSignedOutWithoutARenewal() =
        runBlocking {
            val rig = rig()
            server.script("/api/me", TestServer.jsonError(403, "user_disabled"))
            val error = assertThrows(ApiError::class.java) { runBlocking { rig.client.getMe() } }
            assertTrue(error.isDisabled)
            assertEquals(1, server.requests.size)
            assertEquals(1, rig.signedOut.size)
        }

    @Test
    fun anExpiredTokenIsRenewedBeforeTheRequestGoesOut() =
        runBlocking {
            val rig = rig(OidcFixtures.tokens(server.baseUrl, expiresAtMs = System.currentTimeMillis() - 5_000))
            server.script(OidcFixtures.TOKEN, OidcFixtures.tokenAnswer("access-2", "refresh-2"))
            server.script("/api/me", TestServer.json(OidcFixtures.ME))
            rig.client.getMe()
            assertEquals(listOf<String?>("Bearer access-2"), bearers())
        }

    /** Without a refresher (a pasted token) a JSON 403 no longer signs out, and the proxy's bare 403 still does. */
    @Test
    fun aRevokedDeviceIsNotRenewedAndSignsOut() =
        runBlocking {
            val signedOut = CopyOnWriteArrayList<ApiError>()
            val refresher = CountingRefresher()
            val client =
                ApiClient(
                    RequestFactory(server.baseUrl, "Test phone") { OidcFixtures.mode("t") },
                    OkHttpClient(),
                    onAuthenticationFailure = { signedOut += it },
                    credentialRefresher = refresher,
                )
            server.script("/api/me", TestServer.jsonError(401, "device_revoked"))

            val error = runCatching { client.getMe() }.exceptionOrNull() as ApiError

            assertTrue(error.isDeviceRevoked)
            assertEquals(0, refresher.refreshes)
            assertEquals(1, signedOut.size)
            assertEquals(1, server.requestsTo("/api/me").size)
        }

    @Test
    fun aSessionWithNothingToRenewKeepsTheStatusRules() =
        runBlocking {
            val signedOut = CopyOnWriteArrayList<ApiError>()
            val client =
                ApiClient(
                    RequestFactory(server.baseUrl, "Test phone") { OidcFixtures.mode("t") },
                    OkHttpClient(),
                    onAuthenticationFailure = { signedOut += it },
                )
            server.script("/api/me", TestServer.jsonError(403, "admin_required"))
            runCatching { client.getMe() }
            assertTrue(signedOut.isEmpty())

            server.script("/api/me", TestServer.proxyForbidden())
            runCatching { client.getMe() }
            assertEquals(1, signedOut.size)
        }
}
