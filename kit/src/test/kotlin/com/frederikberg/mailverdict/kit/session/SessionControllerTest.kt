package com.frederikberg.mailverdict.kit.session

import com.frederikberg.mailverdict.kit.OidcFixtures
import com.frederikberg.mailverdict.kit.TestServer
import com.frederikberg.mailverdict.kit.api.getMe
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.auth.InMemoryTokenStore
import com.frederikberg.mailverdict.kit.auth.TokenStore
import com.frederikberg.mailverdict.kit.live.Connectivity
import com.frederikberg.mailverdict.kit.net.ApiClient
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
            "Test phone",
            { cleanup },
            liveUpdates = false,
        )

    /** What the controller is told to undo on the server at sign-out besides the sign-in row. */
    private var cleanup: suspend (ApiClient) -> Unit = { _ -> }

    /** The whole browser round trip, ending signed in; [revocable] makes the provider name a revocation endpoint. */
    private fun signIn(
        controller: SessionController,
        revocable: Boolean = false,
    ) {
        serveAuthConfig()
        if (revocable) server.script(OidcFixtures.DISCOVERY, OidcFixtures.discovery(server, revocable = true))
        val url = runBlocking { controller.beginOidcSignIn(server.baseUrl) }!!
        runBlocking { controller.completeOidcSignIn("$callbacks?code=c&state=${stateOf(url)}") }
    }

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
        tokens.write(AuthMode.Oidc(OidcFixtures.tokens(server.baseUrl, access = "stored")))
        addresses.write(server.baseUrl)
        val controller = controller()
        controller.restore()

        val connection = signedInConnection(controller)
        assertEquals("Owner", runBlocking { connection.api.getMe() }.displayName)
        assertEquals("Bearer stored", server.requestsTo("/api/me").last().headers["Authorization"])
    }

    @Test
    fun anAddressWithoutASchemeIsReportedNotTried() {
        val controller = controller()
        runBlocking { controller.beginOidcSignIn("mail.example.com") }

        val failed = controller.signInStatus.value as SignInStatus.Failed
        assertTrue(failed.message, "https://" in failed.message)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun aServerWithoutSignInOfItsOwnSaysSo() {
        serveAuthConfig(clientId = null)
        val controller = controller()

        assertNull(runBlocking { controller.beginOidcSignIn(server.baseUrl) })

        val failed = controller.signInStatus.value as SignInStatus.Failed
        assertEquals("This server does not offer sign-in from the app.", failed.message)
        assertEquals(SessionState.SignedOut(), controller.state.value)
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
        val controller = controller()
        signIn(controller)
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
        val controller = controller()
        signIn(controller)
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
            SessionController(
                scope,
                OkHttpClient(),
                slowStore,
                addresses,
                connectivity,
                "Test phone",
                { cleanup },
                liveUpdates = false,
            )
        signIn(controller)
        val connection = signedInConnection(controller)
        server.script("/api/me", TestServer.jsonError(401, "token_expired"))

        val first = scope.async { runCatching { connection.api.getMe() } }
        assertTrue(clearing.await(5, TimeUnit.SECONDS))
        runCatching { runBlocking { connection.api.getMe() } }

        assertTrue(controller.state.value is SessionState.SignedOut)
        runBlocking { first.await() }
    }

    @Test
    fun everyRequestCarriesTheDeviceName() {
        val controller = controller()
        signIn(controller)

        runBlocking { signedInConnection(controller).api.getMe() }

        assertEquals("Test phone", server.requestsTo("/api/auth/config").last().headers["X-Device-Name"])
        assertEquals("Test phone", server.requestsTo("/api/me").last().headers["X-Device-Name"])
    }

    /** A revoked device would only get another token for the same device from a renewal, so none is asked for. */
    @Test
    fun aRevokedDeviceIsSignedOutWithoutRenewingTheToken() {
        val controller = controller()
        signIn(controller)
        val connection = signedInConnection(controller)
        server.script("/api/me", TestServer.jsonError(401, "device_revoked"))
        val tokenRequests = server.requestsTo(OidcFixtures.TOKEN).size

        runCatching { runBlocking { connection.api.getMe() } }

        val out = controller.state.value as SessionState.SignedOut
        assertEquals(true, out.rejection?.deviceRevoked)
        assertNull(tokens.read())
        assertEquals(tokenRequests, server.requestsTo(OidcFixtures.TOKEN).size)
    }

    @Test
    fun signingOutAsksTheProviderToRevokeTheRefreshToken() {
        server.script(OidcFixtures.REVOKE, TestServer.status(200))
        val controller = controller()
        signIn(controller, revocable = true)

        controller.signOut()

        val body = awaitBody(OidcFixtures.REVOKE)
        assertTrue(body, "token=refresh-1" in body)
        assertTrue(body, "token_type_hint=refresh_token" in body)
        assertTrue(body, "client_id=${OidcFixtures.CLIENT_ID}" in body)
        assertNull(tokens.read())
    }

    @Test
    fun signingOutDoesNotWaitForAProviderThatIsNotThere() {
        val controller = controller()
        signIn(controller, revocable = true)
        server.close()

        controller.signOut()

        assertEquals(SessionState.SignedOut(), controller.state.value)
        assertNull(tokens.read())
    }

    @Test
    fun aProviderWithoutRevocationIsNotAskedToRevokeAnything() {
        val controller = controller()
        signIn(controller)

        controller.signOut()

        Thread.sleep(300)
        assertTrue(server.requestsTo(OidcFixtures.REVOKE).isEmpty())
    }

    private fun signIns(vararg rows: Triple<String, Boolean, Boolean>): String =
        rows.joinToString(prefix = "[", postfix = "]") { (id, current, revoked) -> signInRow(id, current, revoked) }

    private fun signInRow(
        id: String,
        current: Boolean,
        revoked: Boolean,
    ) = """{"id":"$id","user_id":"u","kind":"phone","name":"Test phone","subject":"s",
        |"first_seen_at":"2026-11-14T08:00:00Z","last_seen_at":"2026-11-14T08:00:00Z",
        |"revoked_at":${if (revoked) "\"2026-11-14T09:00:00Z\"" else "null"},"current":$current}
        """.trimMargin()

    private fun awaitRequests(
        path: String,
        count: Int = 1,
    ) {
        repeat(100) {
            if (server.requestsTo(path).size >= count) return
            Thread.sleep(50)
        }
        error("no request to $path")
    }

    @Test
    fun signingOutRevokesThePhonesOwnRowAndOnlyThatOneAfterTheCleanup() {
        val controller = controller()
        signIn(controller)
        val steps = java.util.concurrent.CopyOnWriteArrayList<String>()
        cleanup = { _ -> steps += "cleanup" }
        server.script(
            "/api/me/sign-ins",
            TestServer.json(signIns(Triple("other", false, false), Triple("mine", true, false))),
        )
        server.script("/api/me/sign-ins/mine/revoke", TestServer.json(signInRow("mine", true, true)))

        controller.signOut()

        awaitRequests("/api/me/sign-ins/mine/revoke")
        assertEquals(listOf("cleanup"), steps)
        assertTrue(server.requestsTo("/api/me/sign-ins/other/revoke").isEmpty())
        assertEquals("POST", server.requestsTo("/api/me/sign-ins/mine/revoke").single().method)
        assertNull(tokens.read())
        assertEquals(SessionState.SignedOut(), controller.state.value)
    }

    @Test
    fun theRowIsRevokedWithTheCredentialStillGoodAndBeforeTheProviderIsAsked() {
        val controller = controller()
        signIn(controller, revocable = true)
        server.script(OidcFixtures.REVOKE, TestServer.status(200))
        server.script("/api/me/sign-ins", TestServer.json(signIns(Triple("mine", true, false))))
        server.script("/api/me/sign-ins/mine/revoke", TestServer.json(signInRow("mine", true, true)))

        controller.signOut()

        awaitRequests(OidcFixtures.REVOKE)
        val order = server.requests.map { it.url.encodedPath }
        val row = order.indexOf("/api/me/sign-ins/mine/revoke")
        assertTrue(order.toString(), row in 0 until order.indexOf(OidcFixtures.REVOKE))
        assertEquals(
            "Bearer access-1",
            server.requestsTo("/api/me/sign-ins/mine/revoke").single().headers["Authorization"],
        )
    }

    @Test
    fun aRowThatIsAlreadyRevokedIsLeftAlone() {
        val controller = controller()
        signIn(controller)
        server.script("/api/me/sign-ins", TestServer.json(signIns(Triple("mine", true, true))))

        controller.signOut()

        awaitRequests("/api/me/sign-ins")
        Thread.sleep(300)
        assertTrue(server.requestsTo("/api/me/sign-ins/mine/revoke").isEmpty())
    }

    @Test
    fun theCleanupStillRunsWhenTheServerCannotListSignIns() {
        val controller = controller()
        signIn(controller)
        val ran =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        cleanup = { _ -> ran.set(true) }
        server.script("/api/me/sign-ins", TestServer.jsonError(500, "boom"))

        controller.signOut()

        awaitRequests("/api/me/sign-ins")
        assertTrue(ran.get())
        assertEquals(SessionState.SignedOut(), controller.state.value)
    }

    @Test
    fun signingOutOfflineSignsOutAtOnce() {
        val controller = controller()
        signIn(controller)
        server.close()
        val started = System.nanoTime()

        controller.signOut()

        assertTrue("sign-out waited for the network", System.nanoTime() - started < 1_000_000_000L)
        assertEquals(SessionState.SignedOut(), controller.state.value)
        assertNull(tokens.read())
    }

    private fun awaitBody(path: String): String {
        repeat(100) {
            server.requestsTo(path).firstOrNull()?.let { return OidcFixtures.bodyOf(it) }
            Thread.sleep(50)
        }
        error("no request to $path")
    }

    @Test
    fun signingOutForgetsTheCredentialButKeepsTheAddress() {
        val controller = controller()
        signIn(controller)

        controller.signOut()

        assertEquals(SessionState.SignedOut(), controller.state.value)
        assertNull(tokens.read())
        assertEquals(server.baseUrl, controller.savedServerUrl)
    }
}
