package com.frederikberg.mailverdict.kit.session

import com.frederikberg.mailverdict.kit.api.getAuthConfig
import com.frederikberg.mailverdict.kit.auth.AuthConfigStore
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.auth.OidcClient
import com.frederikberg.mailverdict.kit.auth.OidcError
import com.frederikberg.mailverdict.kit.auth.OidcTokenHolder
import com.frederikberg.mailverdict.kit.auth.OidcTokens
import com.frederikberg.mailverdict.kit.auth.SignInFlow
import com.frederikberg.mailverdict.kit.auth.TokenRefresher
import com.frederikberg.mailverdict.kit.auth.TokenStore
import com.frederikberg.mailverdict.kit.live.Connectivity
import com.frederikberg.mailverdict.kit.live.LiveEventHub
import com.frederikberg.mailverdict.kit.live.NetworkStatus
import com.frederikberg.mailverdict.kit.live.OkHttpSseTransport
import com.frederikberg.mailverdict.kit.live.SseClient
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.kit.stores.MeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.net.URI

/**
 * Owns the one question the whole app hangs off: is there a credential, and what is built on it.
 *
 * The credential lives in a [TokenStore] (sealed on the device); a restart reads it back and the
 * connection is rebuilt from it, so signing in once is enough until the person signs out or the
 * server refuses the credential for good. The browser leg of an OIDC sign-in belongs to the
 * platform: [beginOidcSignIn] hands back the address to open and [completeOidcSignIn] takes what the
 * browser returned.
 */
class SessionController(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val tokenStore: TokenStore,
    private val addressStore: ServerAddressStore,
    private val connectivity: Connectivity,
    /** What the server's list of signed-in devices calls this phone; sent with every request. */
    private val deviceName: String,
    /** False for fixture mode, which has no event stream to open. */
    private val liveUpdates: Boolean = true,
    /** False for fixture mode, which has no provider to tell about a sign-out. */
    private val revokesAtProvider: Boolean = true,
    private val oidc: OidcClient = OidcClient(httpClient),
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _signInStatus = MutableStateFlow<SignInStatus>(SignInStatus.Idle)
    val signInStatus: StateFlow<SignInStatus> = _signInStatus.asStateFlow()

    private val networkStatus = NetworkStatus(scope, connectivity)
    private val signInFlow = SignInFlow(oidc)
    private val authConfigs =
        AuthConfigStore { url ->
            ApiClient(RequestFactory(url, deviceName) { AuthMode.None }, httpClient).getAuthConfig()
        }

    /** A sign-in whose browser leg is open: held until the browser comes back, never persisted. */
    private class Attempt(
        val serverUrl: String,
        val flow: SignInFlow.Pending,
    )

    private val lock = Any()
    private var pending: Attempt? = null

    @Volatile
    private var current: Connection? = null

    /** The address typed last, to prefill the sign-in screen. */
    val savedServerUrl: String get() = addressStore.read().orEmpty()

    init {
        connectivity.observe { online -> if (online) current?.liveEventHub?.networkPathRestored() }
    }

    /** Brings the connection up from the stored credential, if there is one and it names a usable address. */
    fun restore() {
        val mode = tokenStore.read() ?: return
        val url = addressStore.read() ?: return
        if (mode == AuthMode.None) return
        runCatching { connect(url, mode) }
    }

    /**
     * Starts an OIDC sign-in and returns the address the browser opens, or null after setting
     * [signInStatus] to the reason it could not start.
     */
    suspend fun beginOidcSignIn(serverUrl: String): String? {
        val url = serverUrl.trim()
        _signInStatus.value = SignInStatus.Working
        return try {
            val config = authConfigs.config(url)
            if (config.clientId.isNullOrEmpty()) throw OidcError.SignInUnavailable()
            val started = signInFlow.begin(config)
            addressStore.write(url)
            synchronized(lock) { pending = Attempt(url, started) }
            _signInStatus.value = SignInStatus.WaitingForBrowser
            started.authorizationUrl
        } catch (e: CancellationException) {
            throw e
        } catch (e: RequestFactory.ConfigurationError) {
            fail(e.message.orEmpty())
        } catch (e: OidcError) {
            fail(e.message.orEmpty())
        } catch (e: ApiError) {
            fail(e.userMessage)
        }
    }

    /** [callbackUrl] is the full redirect the browser ended on. */
    suspend fun completeOidcSignIn(callbackUrl: String) {
        val attempt = synchronized(lock) { pending.also { pending = null } }
        if (attempt == null) {
            fail("The sign-in did not complete. Start it again.")
            return
        }
        if (!RedirectUri.matches(attempt.flow.redirectUri, callbackUrl)) {
            fail("The sign-in answer did not belong to this app.")
            return
        }
        _signInStatus.value = SignInStatus.Exchanging
        try {
            val tokens = signInFlow.complete(attempt.flow, callbackUrl)
            val mode = AuthMode.Oidc(tokens)
            tokenStore.write(mode)
            connect(attempt.serverUrl, mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OidcError) {
            fail(e.message.orEmpty())
        } catch (e: RequestFactory.ConfigurationError) {
            fail(e.message.orEmpty())
        }
    }

    /** The device has no browser that can show the sign-in page. */
    fun reportBrowserUnavailable() {
        synchronized(lock) { pending = null }
        fail("No browser is available to sign in with. Install one and try again.")
    }

    /** The person left the browser without finishing. */
    fun cancelSignIn() {
        synchronized(lock) { pending = null }
        _signInStatus.value = SignInStatus.Idle
    }

    /**
     * Forgets the credential on this phone at once, then asks the provider to end the sign-in so the
     * refresh token that was stored is no longer good anywhere. That request is best effort and
     * bounded: signing out never waits for the network, and an unreachable provider leaves the
     * refresh token to expire on its own.
     */
    fun signOut() {
        val tokens = (tokenStore.read() as? AuthMode.Oidc)?.tokens
        closeCurrent()
        tokenStore.clear()
        if (tokens != null && revokesAtProvider) scope.launch { revokeAtProvider(tokens) }
        _signInStatus.value = SignInStatus.Idle
        _state.value = SessionState.SignedOut()
    }

    /** The app left the foreground: the stream closes instead of dying unobserved. */
    fun onBackground() {
        current?.liveEventHub?.pause()
    }

    /** The app is back: the stream reconnects at once and replays what it missed, and the role is read again. */
    fun onForeground() {
        val connection = current ?: return
        connection.liveEventHub.resume()
        scope.launch { connection.me.load() }
    }

    private suspend fun revokeAtProvider(tokens: OidcTokens) {
        try {
            withTimeout(REVOKE_TIMEOUT_MS) { oidc.revoke(tokens, oidc.discover(tokens.issuer)) }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
        } catch (e: OidcError) {
            // Nothing to retry against and nobody to tell: the person has already left.
        }
    }

    private fun fail(message: String): Nothing? {
        _signInStatus.value = SignInStatus.Failed(message)
        return null
    }

    private fun connect(
        serverUrl: String,
        mode: AuthMode,
    ) {
        // Oidc sessions keep their token set in one holder the request factory reads at send time, so a
        // renewed access token reaches the next request; the refresher stores each renewal first.
        val holder = (mode as? AuthMode.Oidc)?.let { OidcTokenHolder(it.tokens) }
        val refresher =
            holder?.let {
                TokenRefresher(
                    scope = scope,
                    holder = it,
                    client = oidc,
                    persist = { tokens -> tokenStore.write(AuthMode.Oidc(tokens)) },
                    reload = { (tokenStore.read() as? AuthMode.Oidc)?.tokens },
                )
            }
        val factory = RequestFactory(serverUrl, deviceName) { holder?.authMode ?: mode }

        lateinit var connection: Connection
        val rejected = { detail: String? -> handleRejection(connection, detail) }
        val api =
            ApiClient(
                factory,
                httpClient,
                onAuthenticationFailure = { error -> rejected(error.userMessage) },
                credentialRefresher = refresher,
            )
        val hub =
            LiveEventHub(scope) { callbacks ->
                SseClient(
                    scope = scope,
                    requests = factory,
                    transport = OkHttpSseTransport(httpClient),
                    callbacks = callbacks,
                    credentialRefresher = refresher,
                    onAuthenticationFailure = { rejected("Your sign-in has ended.") },
                )
            }
        val me = MeStore(api, scope)
        me.follow(hub.events)
        connection = Connection(serverUrl, api, hub, me, networkStatus, scope)

        synchronized(lock) {
            closeCurrent()
            current = connection
        }
        if (liveUpdates) hub.connect()
        scope.launch { me.load() }
        _signInStatus.value = SignInStatus.Idle
        _state.value = SessionState.SignedIn(connection)
    }

    /**
     * The server refused the credential for good: back to the sign-in screen, saying so. Every
     * request in flight reports its own refusal, so this runs many times at once; it ends the session
     * once and the later callers return only after the signed-out state is visible.
     */
    private fun handleRejection(
        connection: Connection,
        detail: String?,
    ): Unit =
        synchronized(lock) {
            if (current !== connection) return
            closeCurrent()
            tokenStore.clear()
            _state.value = SessionState.SignedOut(Rejection(detail))
        }

    private fun closeCurrent() =
        synchronized(lock) {
            current?.liveEventHub?.disconnect()
            current?.me?.clear()
            current = null
        }
}

private const val REVOKE_TIMEOUT_MS = 10_000L

/** Compares the redirect the browser ended on with the one the sign-in asked for: scheme, host and path, never the query. */
object RedirectUri {
    fun matches(
        expected: String,
        actual: String,
    ): Boolean {
        val want = runCatching { URI(expected) }.getOrNull() ?: return false
        val got = runCatching { URI(actual) }.getOrNull() ?: return false
        return want.scheme.equals(got.scheme, ignoreCase = true) &&
            want.rawAuthority.equals(got.rawAuthority, ignoreCase = true) &&
            want.rawPath.orEmpty() == got.rawPath.orEmpty()
    }
}
