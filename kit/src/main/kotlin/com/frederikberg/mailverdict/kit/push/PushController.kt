package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.contract.PushSubscriptionResponse
import com.frederikberg.mailverdict.kit.api.getVapidPublicKey
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** What the phone's side of push is doing, for the screen that explains and controls it. */
sealed interface PushStatus {
    data object Loading : PushStatus

    /** The server could not be asked at all. */
    data class LoadFailed(
        val message: String,
    ) : PushStatus

    /** The server holds no encryption key, so it cannot send Web Push to anyone. */
    data object ServerUnavailable : PushStatus

    /** No distributor app is installed, or none is chosen: the one thing the person has to do first. */
    data object NoDistributor : PushStatus

    data object Off : PushStatus

    /** Asked the distributor for an endpoint; its answer arrives through [PushController.onNewEndpoint]. */
    data object Registering : PushStatus

    data class Registered(
        val subscription: PushSubscriptionResponse,
    ) : PushStatus

    data class Failed(
        val message: String,
    ) : PushStatus
}

/** What only the phone can do: talk to the distributor. The real one wraps the UnifiedPush connector. */
interface PushPlatform {
    /** Whether a distributor app is installed. Choosing between several is the screen's job, before [register]. */
    fun hasDistributor(): Boolean

    /** Asks the chosen distributor for an endpoint; the answer comes back as [PushController.onNewEndpoint]. */
    fun register(
        vapidPublicKey: String,
        label: String,
    )

    fun unregister()
}

/** The connection a controller works through while someone is signed in. */
class PushSession(
    val api: ApiClient,
    val serverUrl: String,
)

/**
 * The state machine behind notifications: turning them on asks the server for its VAPID key and the
 * distributor for an endpoint, the endpoint goes to the server as a subscription, and the server then
 * pushes to the distributor. It lives as long as the app, because a distributor delivers an endpoint
 * to a process that may have just started with no screen open.
 */
class PushController(
    private val registrar: PushRegistrar,
    private val platform: PushPlatform,
    session: () -> PushSession?,
    private val deviceLabel: String,
    private val registrationTimeoutMs: Long = REGISTRATION_TIMEOUT_MS,
) : SubscriptionPushControl(session) {
    override val subscriptionId: String? get() = registrar.record()?.subscriptionId

    /** Works out where things stand and re-asserts a registration that exists, so a changed endpoint is caught. */
    override suspend fun refresh() {
        val current = session() ?: return
        try {
            val subscription = registrar.current(current.api, current.serverUrl)
            if (!platform.hasDistributor()) {
                mutableStatus.value = if (subscription != null) PushStatus.Off else PushStatus.NoDistributor
                return
            }
            if (subscription != null) {
                mutableStatus.value = PushStatus.Registered(subscription)
                requestEndpoint(current)
                return
            }
            mutableStatus.value = if (vapidKey(current) == null) PushStatus.ServerUnavailable else PushStatus.Off
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            mutableStatus.value = PushStatus.LoadFailed(e.userMessage)
        }
    }

    /** The person turned notifications on, with a distributor already chosen. */
    override suspend fun enable() {
        val current = session() ?: return
        if (!platform.hasDistributor()) {
            mutableStatus.value = PushStatus.NoDistributor
            return
        }
        mutableStatus.value = PushStatus.Registering
        try {
            val key = vapidKey(current)
            if (key == null) {
                mutableStatus.value = PushStatus.ServerUnavailable
                return
            }
            platform.register(key, deviceLabel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            mutableStatus.value = PushStatus.Failed(e.userMessage)
            return
        }
        // A distributor that never answers would leave the screen on "Setting up" for good.
        withTimeoutOrNull(registrationTimeoutMs) { mutableStatus.first { it != PushStatus.Registering } }
        mutableStatus.update {
            if (it ==
                PushStatus.Registering
            ) {
                PushStatus.Failed(REGISTRATION_TIMEOUT_MESSAGE)
            } else {
                it
            }
        }
    }

    /** The distributor's answer to a registration: hand the endpoint to the server. */
    suspend fun onNewEndpoint(endpoint: PushEndpointInfo) {
        val current = session() ?: return
        try {
            val subscription = registrar.register(current.api, current.serverUrl, endpoint, deviceLabel)
            mutableStatus.value = PushStatus.Registered(subscription)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            mutableStatus.value = PushStatus.Failed(e.userMessage)
        }
    }

    fun onRegistrationFailed(reason: String) {
        mutableStatus.value = PushStatus.Failed(reason)
    }

    /** The distributor dropped this app: the server's copy is useless, so it goes too. */
    suspend fun onUnregistered() {
        registrar.unregister(session()?.api)
        mutableStatus.update { if (it is PushStatus.Registered || it is PushStatus.Registering) PushStatus.Off else it }
    }

    override suspend fun disable() {
        platform.unregister()
        registrar.unregister(session()?.api)
        mutableStatus.value = PushStatus.Off
    }

    override fun onSignedOut() {
        if (registrar.record() == null && mutableStatus.value == PushStatus.Loading) return
        platform.unregister()
        registrar.forget()
        mutableStatus.value = PushStatus.Loading
    }

    private suspend fun requestEndpoint(current: PushSession) {
        val key = vapidKey(current) ?: return
        platform.register(key, deviceLabel)
    }

    private suspend fun vapidKey(current: PushSession): String? {
        val response = current.api.getVapidPublicKey()
        return response.publicKey?.takeIf { response.available }
    }

    companion object {
        const val REGISTRATION_TIMEOUT_MS = 30_000L
        const val REGISTRATION_TIMEOUT_MESSAGE = "The helper app did not answer. Open it once, then try again."
    }
}
