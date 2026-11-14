package com.frederikberg.mailverdict.kit.session

import com.frederikberg.mailverdict.kit.OidcFixtures
import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.api.getMe
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.auth.InMemoryTokenStore
import com.frederikberg.mailverdict.kit.auth.TokenStore
import com.frederikberg.mailverdict.kit.live.Connectivity
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionControllerTest {
    private val server = TestServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tokens = InMemoryTokenStore()
    private val addresses = InMemoryServerAddressStore()
    private val callbacks = "mailverdict://auth/callback"

    private val connectivity =
        object : Connectivity {
            override val isOnline = true

            override fun observe(listener: (online: Boolean) -> Unit) = Unit
        }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun controller() =
        SessionController(
            scope,
            OkHttpClient(),
            tokens,
            addresses,
            connectivity,
            liveUpdates = false,
        )

    private fun signedInConnection(controller: SessionController) =
        (controller.state.value as SessionState.SignedIn).connection

    private fun serveAuthConfig(clientId: String? = OidcFixtures.CLIENT_ID) {
        val client = clientId?.let { """"$it"""" } ?: "null"
        server.script(
            "/api/auth/config",
            TestServer.json(
                """{"issuer":"${server.baseUrl}","client_id":$client,"scopes":"openid","redirect_uri":"$callbacks"}""",
            ),
        )
        server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server))
        server.script(OidcFixtures.TOKEN, OidcFixtures.tokenAnswer("access-1", "refresh-1"))
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
    }

    private fun stateOf(authorizationUrl: String) = authorizationUrl.toHttpUrl().queryParameter("state")!!

    @Test
    fun nothingStoredStartsSignedOut() {
        val controller = controller()
        controller.restore()

        assertEquals(SessionState.SignedOut(), controller.state.value)
    }

    @Test
    fun aStoredCredentialAndAddressBringTheConnectionBackAfterARestart() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        tokens.write(AuthMode.Bearer("stored"))
        addresses.write(server.baseUrl)
        val controller = controller()
        controller.restore()

        val connection = signedInConnection(controller)
        assertEquals("Owner", runBlocking { connection.api.getMe() }.displayName)
        assertEquals("Bearer stored", server.requestsTo("/api/me").last().headers["Authorization"])
    }

    @Test
    fun aPastedTokenIsTriedBeforeItIsStored() {
        server.script("/api/me", TestServer.jsonError(401, "not_authenticated"))
        val controller = controller()
        runBlocking { controller.signInWithToken(server.baseUrl, "wrong") }

        assertTrue(controller.signInStatus.value is SignInStatus.Failed)
        assertNull(tokens.read())
        assertNull(addresses.read())
        assertEquals(SessionState.SignedOut(), controller.state.value)
    }

    @Test
    fun aGoodPastedTokenSignsInAndIsRemembered() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        val controller = controller()
        runBlocking { controller.signInWithToken("  ${server.baseUrl}  ", " good ") }

        signedInConnection(controller)
        assertEquals(AuthMode.Bearer("good"), tokens.read())
        assertEquals(server.baseUrl, addresses.read())
        assertEquals(SignInStatus.Idle, controller.signInStatus.value)
    }

    @Test
    fun anAddressWithoutASchemeIsReportedNotTried() {
        val controller = controller()
        runBlocking { controller.signInWithToken("mail.example.com", "token") }

        val failed = controller.signInStatus.value as SignInStatus.Failed
        assertTrue(failed.message, "https://" in failed.message)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun aServerWithoutSignInOfItsOwnPointsToTheTokenMode() {
        serveAuthConfig(clientId = null)
        val controller = controller()

        assertNull(runBlocking { controller.beginOidcSignIn(server.baseUrl) })

        assertEquals(true, (controller.signInStatus.value as SignInStatus.Failed).tokenModeSuggested)
    }

    @Test
    fun theWholeBrowserRoundTripEndsSignedInWithTheTokensStored() {
        serveAuthConfig()
        val controller = controller()
        val url = runBlocking { controller.beginOidcSignIn(server.baseUrl) }!!
        assertEquals(SignInStatus.WaitingForBrowser, controller.signInStatus.value)

        runBlocking { controller.completeOidcSignIn("$callbacks?code=the-code&state=${stateOf(url)}") }

        signedInConnection(controller)
        val stored = tokens.read() as AuthMode.Oidc
        assertEquals("access-1", stored.tokens.accessToken)
        assertEquals(server.baseUrl, addresses.read())
        val body = OidcFixtures.bodyOf(server.requestsTo(OidcFixtures.TOKEN).single())
        assertTrue(body, "code=the-code" in body)
    }

    @Test
    fun aCallbackFromAnotherAttemptNeverSpendsTheCode() {
        serveAuthConfig()
        val controller = controller()
        runBlocking { controller.beginOidcSignIn(server.baseUrl) }

        runBlocking { controller.completeOidcSignIn("$callbacks?code=stolen&state=another") }

        assertTrue(controller.signInStatus.value is SignInStatus.Failed)
        assertTrue(server.requestsTo(OidcFixtures.TOKEN).isEmpty())
        assertNull(tokens.read())
    }

    @Test
    fun aCallbackOnAnotherAddressIsRefusedBeforeAnythingIsSpent() {
        serveAuthConfig()
        val controller = controller()
        val url = runBlocking { controller.beginOidcSignIn(server.baseUrl) }!!

        runBlocking { controller.completeOidcSignIn("mailverdict://other/callback?code=c&state=${stateOf(url)}") }

        assertTrue(controller.signInStatus.value is SignInStatus.Failed)
        assertTrue(server.requestsTo(OidcFixtures.TOKEN).isEmpty())
    }

    @Test
    fun aCallbackWithNoAttemptInProgressSaysSoInsteadOfDoingNothing() {
        val controller = controller()
        runBlocking { controller.completeOidcSignIn("$callbacks?code=c&state=s") }

        assertTrue(controller.signInStatus.value is SignInStatus.Failed)
    }

    @Test
    fun leavingTheBrowserWithoutFinishingForgetsTheAttempt() {
        serveAuthConfig()
        val controller = controller()
        val url = runBlocking { controller.beginOidcSignIn(server.baseUrl) }!!
        controller.cancelSignIn()

        runBlocking { controller.completeOidcSignIn("$callbacks?code=c&state=${stateOf(url)}") }

        assertTrue(server.requestsTo(OidcFixtures.TOKEN).isEmpty())
    }

    @Test
    fun aCredentialTheServerRefusesEndsTheSessionAndSaysSo() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        val controller = controller()
        runBlocking { controller.signInWithToken(server.baseUrl, "good") }
        val connection = signedInConnection(controller)
        server.script("/api/me", TestServer.jsonError(401, "token_expired"))

        runCatching { runBlocking { connection.api.getMe() } }

        val out = controller.state.value as SessionState.SignedOut
        assertEquals("token_expired", out.rejection?.detail)
        assertNull(tokens.read())
    }

    /** Every request in flight when the credential dies reports its own refusal; the session ends once, cleanly. */
    @Test
    fun manyRequestsRefusedTogetherEndTheSessionOnce() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        val controller = controller()
        runBlocking { controller.signInWithToken(server.baseUrl, "good") }
        val connection = signedInConnection(controller)
        server.script("/api/me", TestServer.jsonError(401, "token_expired"))
        val states = mutableListOf<SessionState>()
        val watcher = scope.launch { controller.state.collect { states += it } }

        val failures =
            runBlocking {
                (1..40)
                    .map { async(Dispatchers.Default) { runCatching { connection.api.getMe() }.exceptionOrNull() } }
                    .awaitAll()
            }

        assertTrue(failures.joinToString { "$it" }, failures.all { it is ApiError })
        assertEquals(SessionState.SignedOut(Rejection("token_expired")), controller.state.value)
        assertNull(tokens.read())
        watcher.cancel()
        assertEquals(1, states.count { it is SessionState.SignedOut })
    }

    /** A refusal that arrives while another is still ending the session must not return before the sign-in screen is showing. */
    @Test
    fun aSecondRefusalReturnsOnlyOnceTheSessionHasEnded() {
        val clearing = CountDownLatch(1)
        val slowStore =
            object : TokenStore by tokens {
                override fun clear() {
                    clearing.countDown()
                    Thread.sleep(400)
                    tokens.clear()
                }
            }
        val controller =
            SessionController(scope, OkHttpClient(), slowStore, addresses, connectivity, liveUpdates = false)
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        runBlocking { controller.signInWithToken(server.baseUrl, "good") }
        val connection = signedInConnection(controller)
        server.script("/api/me", TestServer.jsonError(401, "token_expired"))

        val first = scope.async { runCatching { connection.api.getMe() } }
        assertTrue(clearing.await(5, TimeUnit.SECONDS))
        runCatching { runBlocking { connection.api.getMe() } }

        assertTrue(controller.state.value is SessionState.SignedOut)
        runBlocking { first.await() }
    }

    @Test
    fun signingOutForgetsTheCredentialButKeepsTheAddress() {
        server.script("/api/me", TestServer.json(OidcFixtures.ME))
        val controller = controller()
        runBlocking { controller.signInWithToken(server.baseUrl, "good") }

        controller.signOut()

        assertEquals(SessionState.SignedOut(), controller.state.value)
        assertNull(tokens.read())
        assertEquals(server.baseUrl, controller.savedServerUrl)
    }
}
