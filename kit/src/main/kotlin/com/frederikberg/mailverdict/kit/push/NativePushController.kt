package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.contract.NativeSubscriptionCreate
import com.frederikberg.mailverdict.kit.api.deletePushSubscription
import com.frederikberg.mailverdict.kit.api.getNativePushConfig
import com.frederikberg.mailverdict.kit.api.listPushSubscriptions
import com.frederikberg.mailverdict.kit.api.registerNativeSubscription
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** What a push that reached the phone asks of it. */
sealed interface PushReceipt {
    /** Show [payload], and withdraw the notifications of the alerts in [resolved]. */
    data class Show(
        val payload: PushPayload,
        val resolved: List<String>,
    ) : PushReceipt

    /** The server wants notifications of alerts read elsewhere withdrawn; it names none. */
    data object ReadSync : PushReceipt

    /** Nothing to show: no registration here, or a push of a kind this version does not know. */
    data object Ignore : PushReceipt
}

/**
 * Notifications through Google's push service and the push relay, the way the iPhone app gets them.
 * Turning them on takes this install's push token to the relay, which answers with a sealed ticket;
 * the ticket and a content key generated here go to the user's own server, which seals every alert
 * with that key and posts it to the relay under the ticket. The relay and Google carry bytes only this
 * phone can open.
 *
 * A registration is kept current without the person: it is made again when the push token changed,
 * when the relay's ticket is about to expire, and when the server no longer lists it (a ticket that
 * lapsed while the app was not opened is removed there). Only [disable] and signing out end it.
 */
class NativePushController(
    private val relay: PushRelayClient,
    private val fcm: FcmPlatform,
    private val records: NativePushRecordStore,
    private val legacy: LegacyPushCleanup,
    session: () -> PushSession?,
    private val deviceLabel: String,
    /** The app name the relay knows this app by. */
    private val relayApp: String = RELAY_APP,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : SubscriptionPushControl(session) {
    override val subscriptionId: String? get() = records.read()?.subscriptionId

    override suspend fun refresh() {
        val current = session() ?: return
        try {
            val migrated = legacy.removeLegacy(current.api)
            val record = records.read()?.takeIf { it.serverUrl == current.serverUrl }
            if (record == null) {
                if (migrated) register(current, null, silent = false) else mutableStatus.value = idleStatus(current)
                return
            }
            val subscription = current.api.listPushSubscriptions().firstOrNull { it.id == record.subscriptionId }
            if (subscription == null) {
                register(current, record, silent = false)
                return
            }
            mutableStatus.value = PushStatus.Registered(subscription)
            val token = fcm.token()
            if (token != null && (token != record.pushToken || record.expiresSoon())) {
                register(current, record, silent = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            mutableStatus.value = PushStatus.LoadFailed(e.userMessage)
        }
    }

    override suspend fun enable() {
        val current = session() ?: return
        register(current, records.read()?.takeIf { it.serverUrl == current.serverUrl }, silent = false)
    }

    /** Google's service issued this install a new token: the relay's ticket belongs to the old one. */
    suspend fun onTokenChanged() {
        val current = session() ?: return
        val record = records.read()?.takeIf { it.serverUrl == current.serverUrl } ?: return
        register(current, record, silent = true)
    }

    override suspend fun disable() {
        val record = records.read()
        records.clear()
        val api = session()?.api
        if (record != null && api != null) {
            try {
                api.deletePushSubscription(record.subscriptionId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiError) {
                // The server drops a device its relay can no longer reach; nothing worse is left behind.
            }
        }
        fcm.forgetToken()
        mutableStatus.value = PushStatus.Off
    }

    override fun onSignedOut() {
        if (records.read() == null && mutableStatus.value == PushStatus.Loading) return
        records.clear()
        fcm.forgetToken()
        mutableStatus.value = PushStatus.Loading
    }

    /** What a push that arrived as [type] with [blob] asks the phone to do. */
    fun receive(
        type: String?,
        blob: String?,
    ): PushReceipt =
        when (type) {
            TYPE_BACKGROUND -> if (records.read() != null) PushReceipt.ReadSync else PushReceipt.Ignore
            TYPE_ALERT -> open(blob)
            else -> PushReceipt.Ignore
        }

    private fun open(blob: String?): PushReceipt {
        val record = records.read() ?: return PushReceipt.Ignore
        val alert =
            try {
                NativeAlert.parse(PushEnvelope.open(blob.orEmpty(), record.contentKeyBytes, record.installationId))
            } catch (_: PushEnvelopeException) {
                // Something arrived for this phone but would not open: still say so rather than drop it.
                return PushReceipt.Show(PushPayload(), emptyList())
            }
        return PushReceipt.Show(alert.toPayload(), alert.resolved)
    }

    private suspend fun idleStatus(current: PushSession): PushStatus =
        try {
            if (current.api.getNativePushConfig().available) PushStatus.Off else PushStatus.ServerUnavailable
        } catch (e: ApiError) {
            PushStatus.LoadFailed(e.userMessage)
        }

    /**
     * Registers with the relay and then the server. A [silent] registration keeps the screen as it is
     * when it fails (the old one still works for now) and leaves the reason as a notice.
     */
    private suspend fun register(
        current: PushSession,
        previous: NativePushRecord?,
        silent: Boolean,
    ) {
        if (!silent) mutableStatus.value = PushStatus.Registering
        try {
            val config = current.api.getNativePushConfig()
            val relayUrl = config.relayUrls.firstOrNull()?.takeIf { config.available }
            if (relayUrl == null) {
                if (!silent) mutableStatus.value = PushStatus.ServerUnavailable
                return
            }
            val token = fcm.token() ?: throw PushRelayException(NO_TOKEN_MESSAGE)
            val ticket = relay.register(relayUrl, relayApp, PLATFORM, token)
            val installationId = previous?.installationId ?: UUID.randomUUID().toString()
            val contentKey = previous?.contentKey ?: newContentKey()
            val subscription =
                current.api.registerNativeSubscription(
                    NativeSubscriptionCreate(installationId, relayUrl, ticket.ticket, contentKey, deviceLabel),
                )
            records.write(
                NativePushRecord(
                    current.serverUrl,
                    installationId,
                    contentKey,
                    subscription.id,
                    relayUrl,
                    token,
                    ticket.expiresAtMs,
                ),
            )
            mutableStatus.value = PushStatus.Registered(subscription)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PushRelayException) {
            failed(e.message.orEmpty(), silent)
        } catch (e: ApiError) {
            failed(e.userMessage, silent)
        }
    }

    private fun failed(
        message: String,
        silent: Boolean,
    ) {
        if (silent) mutableNotice.value = message else mutableStatus.value = PushStatus.Failed(message)
    }

    private fun NativePushRecord.expiresSoon() = ticketExpiresAtMs - nowMs() < RENEW_BEFORE_EXPIRY_MS

    private fun newContentKey(): String =
        Base64.getEncoder().encodeToString(ByteArray(PushEnvelope.CONTENT_KEY_BYTES).also(random::nextBytes))

    companion object {
        /** The name the push relay serves this app under. */
        const val RELAY_APP = "mailverdict"
        const val PLATFORM = "fcm"
        const val TYPE_ALERT = "alert"
        const val TYPE_BACKGROUND = "background"
        const val NO_TOKEN_MESSAGE = "Google Play services did not give this phone a push token. Try again later."

        /** A ticket is made again this long before it expires; the relay issues them for about three months. */
        const val RENEW_BEFORE_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000
    }
}
