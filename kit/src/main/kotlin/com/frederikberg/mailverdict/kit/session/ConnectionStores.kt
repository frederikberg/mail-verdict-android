package com.frederikberg.mailverdict.kit.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Holds what a screen area keeps for as long as one sign-in lasts: its stores, so leaving a list for
 * the reader and coming back finds the same rows, and a rotation does not read everything again. A
 * different connection, or signing out, cancels everything the previous one started.
 */
class ConnectionStores(
    private val parent: CoroutineScope,
    state: StateFlow<SessionState>,
    /** Runs when the session ends: nothing of a signed-out account stays on the device. */
    private val onSignedOut: suspend () -> Unit = {},
) {
    private var owner: Connection? = null
    private var scope: CoroutineScope? = null
    private val stores = HashMap<Any, Any>()

    init {
        parent.launch {
            var previous: Connection? = null
            state.collect { current ->
                val connection = (current as? SessionState.SignedIn)?.connection
                if (connection !== previous) {
                    release()
                    if (connection == null) onSignedOut()
                    previous = connection
                }
            }
        }
    }

    /** The store for [key] under [connection], built by [create] on first use with a scope that ends when the connection does. */
    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(
        connection: Connection,
        key: Any,
        create: (CoroutineScope) -> T,
    ): T {
        if (owner !== connection) {
            release()
            owner = connection
        }
        val storeScope =
            scope
                ?: CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job])).also {
                    scope =
                        it
                }
        return stores.getOrPut(key) { create(storeScope) } as T
    }

    @Synchronized
    private fun release() {
        scope?.cancel()
        scope = null
        owner = null
        stores.clear()
    }
}
