package com.frederikberg.mailverdict.kit.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicReference

/** What a request found out about its credential, as far as the person is concerned. */
enum class RefreshOutcome {
    /** The token in use is fresh; send the request again. */
    Refreshed,

    /** The provider refused the refresh token: the session is over and the person must sign in. */
    Rejected,

    /** The provider could not be reached or did not answer sensibly. The credential is not known to be bad, so nothing signs out. */
    Unavailable,
}

/**
 * What the API client and the event stream ask when the server refuses their bearer token. An
 * interface so a client can be built, and tested, without an OpenID provider.
 */
interface CredentialRefreshing {
    /** Renews a token that is expired or about to be, before a request goes out with it. */
    suspend fun refreshIfNeeded()

    /**
     * Renews after a request under [accessToken] was refused. Answers [RefreshOutcome.Refreshed]
     * without asking the provider when the token has already been replaced since, so several requests
     * refused together renew once.
     */
    suspend fun refresh(afterRejectionOf: String?): RefreshOutcome
}

/** The token set currently in use, readable from the synchronous provider a [net.RequestFactory] takes its credential from. */
class OidcTokenHolder(
    initial: OidcTokens,
) {
    private val current = AtomicReference(initial)

    var tokens: OidcTokens
        get() = current.get()
        set(value) = current.set(value)

    /** The credential a request factory's auth provider returns. */
    val authMode: AuthMode get() = AuthMode.Oidc(tokens)
}

/**
 * Keeps one sign-in alive: renews the access token when the server refuses it, and stores each new
 * token set the moment it arrives.
 *
 * Single-flight: however many requests find the token refused at once, the provider is asked once and
 * all of them take its answer. That matters beyond tidiness. Zitadel rotates refresh tokens, so two
 * simultaneous refreshes with the same one would leave the loser holding a token the winner just
 * retired. The renewal runs in [scope] and not in the first caller, so a caller that is cancelled
 * midway cannot abandon an exchange whose answer carries the only valid refresh token.
 */
class TokenRefresher(
    private val scope: CoroutineScope,
    private val holder: OidcTokenHolder,
    private val client: OidcClient,
    /** Stores a new token set. Called before the new tokens are put to use, so a crash between the two cannot lose a rotated refresh token. */
    private val persist: (OidcTokens) -> Unit,
    /** The token set the store holds right now; null when it holds none. Another component may have renewed it. */
    private val reload: () -> OidcTokens? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CredentialRefreshing {
    private val lock = Any()
    private var metadata: OidcProviderMetadata? = null
    private var inFlight: Deferred<RefreshOutcome>? = null

    /**
     * The refresh token the provider last refused. Requests already in flight when the session ended
     * all report their own 401; none of them should ask the provider again.
     */
    private var refusedRefreshToken: String? = null

    override suspend fun refreshIfNeeded() {
        val tokens = holder.tokens
        if (tokens.refreshToken == null || !tokens.isExpired(nowMs())) return
        refresh(afterRejectionOf = null)
    }

    override suspend fun refresh(afterRejectionOf: String?): RefreshOutcome {
        val pending: Deferred<RefreshOutcome> =
            synchronized(lock) {
                inFlight?.let { return@synchronized it }
                val tokens = holder.tokens
                if (afterRejectionOf != null && afterRejectionOf != tokens.accessToken) {
                    return@synchronized CompletableDeferred(RefreshOutcome.Refreshed)
                }
                if (tokens.refreshToken != null && tokens.refreshToken == refusedRefreshToken) {
                    return@synchronized CompletableDeferred(RefreshOutcome.Rejected)
                }
                scope.async { renew() }.also { deferred ->
                    inFlight = deferred
                    deferred.invokeOnCompletion { synchronized(lock) { if (inFlight === deferred) inFlight = null } }
                }
            }
        return pending.await()
    }

    private suspend fun renew(): RefreshOutcome {
        reload()?.let { stored ->
            if (stored != holder.tokens) {
                holder.tokens = stored
                if (!stored.isExpired(nowMs())) return RefreshOutcome.Refreshed
            }
        }
        val tokens = holder.tokens
        if (tokens.refreshToken == null) return RefreshOutcome.Rejected
        return try {
            val provider = metadata ?: client.discover(tokens.issuer).also { metadata = it }
            val renewed = client.refresh(tokens, provider)
            persist(renewed)
            holder.tokens = renewed
            RefreshOutcome.Refreshed
        } catch (e: CancellationException) {
            throw e
        } catch (e: OidcError) {
            if (e.isRejection) {
                synchronized(lock) { refusedRefreshToken = tokens.refreshToken }
                RefreshOutcome.Rejected
            } else {
                RefreshOutcome.Unavailable
            }
        }
    }
}
