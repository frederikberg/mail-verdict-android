package com.frederikberg.mailverdict.kit.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Whether the server can be reached right now, as every screen shows it: the one answer the list and the reader both read, so they never disagree about being offline. */
enum class Reachability {
    Online,

    /** The device has no network path at all. */
    Offline,

    /** The device has a path, but the server's live stream has not come back within its grace period: the server, a VPN or the proxy in front of it is down. */
    ServerUnreachable,

    ;

    companion object {
        /** [stream] is [LiveEventHub.visibleConnectionState], already past its grace period. */
        fun of(
            pathAvailable: Boolean,
            stream: ConnectionState,
        ): Reachability =
            when {
                !pathAvailable -> Offline
                stream == ConnectionState.Connected -> Online
                stream == ConnectionState.Reconnecting -> ServerUnreachable
                else -> Offline
            }
    }
}

/** What the platform tells the app about the device's network path. Implemented over `ConnectivityManager` in the app. */
interface Connectivity {
    val isOnline: Boolean

    /** Calls [listener] with the new state on every change; the registration lasts as long as the process. */
    fun observe(listener: (online: Boolean) -> Unit)
}

/**
 * The device's network path as state a view can observe. A path lost for less than [offlineGraceMs]
 * does not count: a handover between Wi-Fi and cellular reports a brief loss, and showing "offline"
 * for it would flash a banner for nothing. A path coming back counts at once.
 */
class NetworkStatus(
    private val scope: CoroutineScope,
    connectivity: Connectivity,
    private val offlineGraceMs: Long = DEFAULT_OFFLINE_GRACE_MS,
) {
    private val _pathAvailable = MutableStateFlow(connectivity.isOnline)
    val pathAvailable: StateFlow<Boolean> = _pathAvailable

    private var pendingLoss: Job? = null

    init {
        connectivity.observe(::pathChanged)
    }

    @Synchronized
    private fun pathChanged(online: Boolean) {
        pendingLoss?.cancel()
        pendingLoss = null
        if (online) {
            _pathAvailable.value = true
            return
        }
        pendingLoss =
            scope.launch {
                delay(offlineGraceMs)
                _pathAvailable.value = false
            }
    }

    companion object {
        const val DEFAULT_OFFLINE_GRACE_MS: Long = 2_000
    }
}
