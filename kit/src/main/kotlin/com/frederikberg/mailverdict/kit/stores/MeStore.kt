package com.frederikberg.mailverdict.kit.stores

import com.frederikberg.mailverdict.contract.MeResponse
import com.frederikberg.mailverdict.kit.api.getMe
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Who is signed in and whether they administer the server, from `GET /api/me`. Whatever decides
 * which screens to offer reads this: until the answer arrives nobody is an admin, so an admin
 * screen never flashes up for a user whose role is still unknown.
 */
class MeStore(
    private val api: ApiClient,
    private val scope: CoroutineScope,
) {
    private val _me = MutableStateFlow<MeResponse?>(null)
    val me: StateFlow<MeResponse?> = _me.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val isAdmin: Boolean get() = _me.value?.isAdmin ?: false

    /** Keeps the last answer when a reload fails, so a dropped connection does not take an admin's screens away mid-use. */
    suspend fun load() {
        try {
            _me.value = api.getMe()
            _errorMessage.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _errorMessage.value = e.userMessage
        }
    }

    /** Forgets who was signed in, on sign-out. */
    fun clear() {
        _me.value = null
        _errorMessage.value = null
    }

    /**
     * A role change announces nothing on the stream, so the app also loads on connect and on
     * returning to the foreground; a resync or a settings change reloads it here.
     */
    fun follow(events: Flow<List<LiveEvent>>) {
        scope.launch {
            events.collect { batch ->
                val stale =
                    batch.any {
                        it.invalidation is LiveInvalidation.Resync ||
                            it.invalidation is LiveInvalidation.SettingsChanged
                    }
                if (stale) load()
            }
        }
    }
}
