package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.contract.PushChannel
import com.frederikberg.mailverdict.contract.PushSubscriptionUpdate
import com.frederikberg.mailverdict.kit.api.deletePushSubscription
import com.frederikberg.mailverdict.kit.api.sendTestPush
import com.frederikberg.mailverdict.kit.api.updatePushSubscription
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which way notifications reach this phone. */
enum class PushBackend {
    /** Google's push service, through the push relay. */
    Fcm,

    /** A helper app (a UnifiedPush distributor) the person installed. */
    UnifiedPush,
}

/**
 * Which backend a phone uses: Google's when the build carries its project configuration and Google Play
 * services answer, the helper-app path otherwise. A phone with neither a helper app nor Google gets
 * live updates while the app is open, and the helper-app screen says what to install.
 */
object PushBackendChoice {
    fun decide(
        firebaseConfigured: Boolean,
        playServicesAvailable: Boolean,
    ): PushBackend = if (firebaseConfigured && playServicesAvailable) PushBackend.Fcm else PushBackend.UnifiedPush
}

/** What the notification screens and the alert list ask of whichever backend this phone uses. */
interface PushControl {
    val status: StateFlow<PushStatus>

    /** The outcome of the last test push or settings change, in words; null when there is nothing to say. */
    val notice: StateFlow<String?>

    /** The server-side id of this phone's subscription, for the bell's own badge scope. */
    val subscriptionId: String?

    /** Works out where things stand and keeps a registration that exists current. */
    suspend fun refresh()

    suspend fun enable()

    suspend fun disable()

    /**
     * What removes this phone's subscription from the server, to run while the sign-in still works. The
     * subscription's id is captured now, so it can run after [onSignedOut] has made the phone forget it.
     */
    fun ownSubscriptionRemoval(): suspend (ApiClient) -> Unit

    /** Sign-out: nothing can be told to the server any more; the phone stops listening and forgets. */
    fun onSignedOut()

    suspend fun setMuted(
        channel: PushChannel,
        muted: Boolean,
    )

    suspend fun sendTest()

    fun clearNotice()
}

/** What both backends share: the state they publish, and the two things done to a subscription that has an id. */
abstract class SubscriptionPushControl(
    protected val session: () -> PushSession?,
) : PushControl {
    protected val mutableStatus = MutableStateFlow<PushStatus>(PushStatus.Loading)
    override val status: StateFlow<PushStatus> = mutableStatus.asStateFlow()

    protected val mutableNotice = MutableStateFlow<String?>(null)
    override val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    override fun ownSubscriptionRemoval(): suspend (ApiClient) -> Unit {
        val id = subscriptionId ?: return {}
        return { api ->
            try {
                api.deletePushSubscription(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiError) {
                // The server drops a subscription it can no longer reach.
            }
        }
    }

    override suspend fun setMuted(
        channel: PushChannel,
        muted: Boolean,
    ) {
        val current = session() ?: return
        val subscription = (mutableStatus.value as? PushStatus.Registered)?.subscription ?: return
        val channels =
            if (muted) {
                (subscription.mutedChannels + channel).distinct()
            } else {
                subscription.mutedChannels - channel
            }
        try {
            val updated =
                current.api.updatePushSubscription(subscription.id, PushSubscriptionUpdate(mutedChannels = channels))
            mutableStatus.value = PushStatus.Registered(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            mutableNotice.value = e.userMessage
        }
    }

    override suspend fun sendTest() {
        val current = session() ?: return
        val subscription = (mutableStatus.value as? PushStatus.Registered)?.subscription ?: return
        mutableNotice.value =
            try {
                current.api.sendTestPush(subscription.id)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                // The server removes a subscription it was told is gone; show the screen as it now is.
                if (e.statusCode == GONE) mutableStatus.value = PushStatus.Off
                e.userMessage
            }
    }

    override fun clearNotice() {
        mutableNotice.value = null
    }

    private companion object {
        const val GONE = 410
    }
}
