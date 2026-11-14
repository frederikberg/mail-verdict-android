package com.frederikberg.mailverdict.push

import com.frederikberg.mailverdict.MailVerdictApp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.kit.push.PushEndpointInfo
import com.frederikberg.mailverdict.kit.push.PushPayload
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import timber.log.Timber

/**
 * Where the distributor delivers: a new endpoint to hand to the server, a decrypted Web Push to show,
 * or the news that the registration is gone. The process may have been started for just this call, so
 * everything goes through the app's long-lived graph.
 */
class MailVerdictPushService : PushService() {
    private val graph get() = (application as MailVerdictApp).currentGraph()

    override fun onNewEndpoint(
        endpoint: PushEndpoint,
        instance: String,
    ) {
        // A temporary endpoint comes from a fallback distributor used while the primary one is away.
        if (endpoint.temporary) return
        val keys = endpoint.pubKeySet
        if (keys == null) {
            graph.push.onRegistrationFailed(getString(R.string.push_no_keys))
            return
        }
        Timber.i("push endpoint received")
        graph.appScope.launch { graph.push.onNewEndpoint(PushEndpointInfo(endpoint.url, keys.pubKey, keys.auth)) }
    }

    override fun onMessage(
        message: PushMessage,
        instance: String,
    ) {
        // Content that did not decrypt is not the expected JSON: show it as a bare notification.
        val payload = if (message.decrypted) PushPayload.parse(message.content) else PushPayload()
        Timber.i("push message received, decrypted=%s", message.decrypted)
        PushNotifier(this).show(payload)
    }

    override fun onRegistrationFailed(
        reason: FailedReason,
        instance: String,
    ) {
        Timber.w("push registration failed: %s", reason)
        graph.push.onRegistrationFailed(
            getString(
                when (reason) {
                    FailedReason.NETWORK -> R.string.push_failed_network
                    FailedReason.ACTION_REQUIRED -> R.string.push_failed_action_required
                    FailedReason.VAPID_REQUIRED -> R.string.push_failed_vapid
                    FailedReason.INTERNAL_ERROR -> R.string.push_failed_internal
                },
            ),
        )
    }

    override fun onUnregistered(instance: String) {
        graph.appScope.launch { graph.push.onUnregistered() }
    }
}
