package com.frederikberg.mailverdict.push

import com.frederikberg.mailverdict.MailVerdictApp
import com.frederikberg.mailverdict.kit.push.PushReceipt
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Where Google's push service delivers: a new token for the relay's ticket to follow, or a sealed alert
 * to open and show. The process may have been started for just this call, so everything goes through
 * the app's long-lived graph.
 */
class MailVerdictFcmService : FirebaseMessagingService() {
    private val graph get() = (application as MailVerdictApp).currentGraph()

    override fun onNewToken(token: String) {
        val native = graph.nativePush ?: return
        Timber.i("push token renewed")
        graph.appScope.launch { native.onTokenChanged() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val native = graph.nativePush ?: return
        val type = message.data[DATA_TYPE]
        Timber.i("push received, type=%s", type)
        val notifier = PushNotifier(this)
        when (val receipt = native.receive(type, message.data[DATA_BLOB])) {
            is PushReceipt.Show -> {
                notifier.show(receipt.payload)
                receipt.resolved.forEach(notifier::cancel)
            }
            PushReceipt.ReadSync -> runBlocking { withTimeoutOrNull(READ_SYNC_TIMEOUT_MS) { graph.readSync(notifier) } }
            PushReceipt.Ignore -> Unit
        }
    }

    private companion object {
        const val DATA_TYPE = "type"
        const val DATA_BLOB = "b"

        /** The service is given about twenty seconds; the read-sync is a convenience and must leave room. */
        const val READ_SYNC_TIMEOUT_MS = 10_000L
    }
}
