package com.frederikberg.mailverdict.kit.session

import com.frederikberg.mailverdict.kit.live.LiveEventHub
import com.frederikberg.mailverdict.kit.live.NetworkStatus
import com.frederikberg.mailverdict.kit.live.Reachability
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.stores.MeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The credential was refused, or the user was disabled; [detail] is what the server said, if anything. */
data class Rejection(
    val detail: String?,
)

/** Whether the app has a credential to work with. */
sealed interface SessionState {
    /** [rejection] is set when a stored credential was refused, so the screen can say so instead of reading as a first launch. */
    data class SignedOut(
        val rejection: Rejection? = null,
    ) : SessionState

    data class SignedIn(
        val connection: Connection,
    ) : SessionState
}

/** Where a sign-in attempt stands, for the sign-in screen. */
sealed interface SignInStatus {
    data object Idle : SignInStatus

    /** Asking the server which provider to use, or checking a pasted token. */
    data object Working : SignInStatus

    /** The browser is open on the provider's page; nothing comes back until the person finishes there. */
    data object WaitingForBrowser : SignInStatus

    /** The browser returned; the code is being traded for a token. */
    data object Exchanging : SignInStatus

    /** [message] is in words for the person; [tokenModeSuggested] when the server offers no sign-in of its own. */
    data class Failed(
        val message: String,
        val tokenModeSuggested: Boolean = false,
    ) : SignInStatus
}

/**
 * Everything that exists only while signed in: one API client, one live stream, shared by every
 * screen. A new sign-in builds a new connection and the old one is closed first, so no second stream
 * is left reading nothing.
 */
class Connection(
    val serverUrl: String,
    val api: ApiClient,
    val liveEventHub: LiveEventHub,
    val me: MeStore,
    networkStatus: NetworkStatus,
    scope: CoroutineScope,
) {
    /** Whether the server can be reached, as every screen shows it. */
    val reachability: StateFlow<Reachability> =
        combine(networkStatus.pathAvailable, liveEventHub.visibleConnectionState) { path, stream ->
            Reachability.of(path, stream)
        }.stateIn(scope, SharingStarted.Eagerly, Reachability.Online)
}
