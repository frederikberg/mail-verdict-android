package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.contract.PushSubscriptionCreate
import com.frederikberg.mailverdict.contract.PushSubscriptionKeys
import com.frederikberg.mailverdict.contract.PushSubscriptionResponse
import com.frederikberg.mailverdict.kit.api.createPushSubscription
import com.frederikberg.mailverdict.kit.api.deletePushSubscription
import com.frederikberg.mailverdict.kit.api.listPushSubscriptions
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** What this phone knows about its own subscription: which server holds it, under which id and endpoint. */
@Serializable
data class PushRecord(
    val serverUrl: String,
    val subscriptionId: String,
    val endpoint: String,
)

interface PushRecordStore {
    fun read(): PushRecord?

    fun write(record: PushRecord)

    fun clear()
}

class InMemoryPushRecordStore(
    private var record: PushRecord? = null,
) : PushRecordStore {
    override fun read() = record

    override fun write(record: PushRecord) {
        this.record = record
    }

    override fun clear() {
        record = null
    }
}

/** The key material and endpoint the distributor handed over; [pubKey] and [auth] are base64url without padding. */
data class PushEndpointInfo(
    val url: String,
    val pubKey: String,
    val auth: String,
)

/**
 * Keeps the server's list of this phone's push endpoints right. The subscription is a plain Web Push
 * one: the distributor's endpoint plus the key set the connector generated, which the server encrypts
 * to; nothing relay-specific exists on either side.
 */
class PushRegistrar(
    private val records: PushRecordStore,
) {
    /** Registers [endpoint] with the server, replacing the one this phone registered before if it differs. */
    suspend fun register(
        api: ApiClient,
        serverUrl: String,
        endpoint: PushEndpointInfo,
        label: String,
    ): PushSubscriptionResponse {
        val previous = records.read()
        val created = api.createPushSubscription(request(endpoint, label))
        records.write(PushRecord(serverUrl, created.id, endpoint.url))
        if (previous != null && previous.serverUrl == serverUrl && previous.subscriptionId != created.id) {
            deleteQuietly(api, previous.subscriptionId)
        }
        return created
    }

    /**
     * This phone's subscription as the server lists it now, or null when there is none: it was
     * deleted there (a push the distributor refused removes it) or this server never had one.
     */
    suspend fun current(
        api: ApiClient,
        serverUrl: String,
    ): PushSubscriptionResponse? {
        val record = records.read()?.takeIf { it.serverUrl == serverUrl } ?: return null
        val found = api.listPushSubscriptions().firstOrNull { it.id == record.subscriptionId }
        if (found == null) records.clear()
        return found
    }

    /** Removes the subscription from the server and forgets it here. Forgets it here even when the server cannot be reached. */
    suspend fun unregister(api: ApiClient?) {
        val record = records.read() ?: return
        records.clear()
        if (api != null) deleteQuietly(api, record.subscriptionId)
    }

    fun record(): PushRecord? = records.read()

    fun forget() = records.clear()

    private suspend fun deleteQuietly(
        api: ApiClient,
        id: String,
    ) {
        try {
            api.deletePushSubscription(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            // The server removes an endpoint that stops answering; a failed delete leaves nothing worse behind.
        }
    }

    companion object {
        /** The body of `POST /api/alerts/subscriptions` for a distributor's endpoint. */
        fun request(
            endpoint: PushEndpointInfo,
            label: String,
        ) = PushSubscriptionCreate(
            endpoint = endpoint.url,
            keys = PushSubscriptionKeys(auth = endpoint.auth, p256dh = endpoint.pubKey),
            label = label,
        )
    }
}
