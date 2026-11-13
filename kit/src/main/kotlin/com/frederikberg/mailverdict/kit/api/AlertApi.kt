package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.AlertBadgeResponse
import com.frederikberg.mailverdict.contract.AlertResponse
import com.frederikberg.mailverdict.contract.AlertUnseenCountResponse
import com.frederikberg.mailverdict.contract.NativePushConfigResponse
import com.frederikberg.mailverdict.contract.PushSubscriptionCreate
import com.frederikberg.mailverdict.contract.PushSubscriptionResponse
import com.frederikberg.mailverdict.contract.PushSubscriptionUpdate
import com.frederikberg.mailverdict.contract.VapidPublicKeyResponse
import com.frederikberg.mailverdict.kit.net.ApiClient
import kotlinx.serialization.builtins.ListSerializer

suspend fun ApiClient.listAlerts(
    limit: Int? = null,
    unseenOnly: Boolean? = null,
): List<AlertResponse> =
    send(
        ListSerializer(AlertResponse.serializer()),
        "/alerts",
        query =
            query(
                "limit" to limit,
                "unseen_only" to unseenOnly,
            ),
    )

suspend fun ApiClient.getUnseenCount(): AlertUnseenCountResponse =
    send(
        AlertUnseenCountResponse.serializer(),
        "/alerts/unseen-count",
    )

/** The number a notification badge shows for this push subscription. */
suspend fun ApiClient.getAlertBadge(subscriptionId: String? = null): AlertBadgeResponse =
    send(AlertBadgeResponse.serializer(), "/alerts/badge", query = query("subscription_id" to subscriptionId))

suspend fun ApiClient.dismissAlert(alertId: String) = sendNoContent("/alerts/$alertId/dismiss", "POST")

/** Dismisses every alert, or only the given kinds. */
suspend fun ApiClient.dismissAllAlerts(kinds: Collection<String>? = null) =
    sendNoContent("/alerts/dismiss-all", "POST", query = repeated("kind", kinds))

/** `available` is false when the server holds no encryption key, so it cannot send Web Push at all. */
suspend fun ApiClient.getVapidPublicKey(): VapidPublicKeyResponse =
    send(
        VapidPublicKeyResponse.serializer(),
        "/alerts/vapid-public-key",
    )

suspend fun ApiClient.getNativePushConfig(): NativePushConfigResponse =
    send(
        NativePushConfigResponse.serializer(),
        "/alerts/native-push",
    )

suspend fun ApiClient.listPushSubscriptions(): List<PushSubscriptionResponse> =
    send(ListSerializer(PushSubscriptionResponse.serializer()), "/alerts/subscriptions")

suspend fun ApiClient.createPushSubscription(request: PushSubscriptionCreate): PushSubscriptionResponse =
    send(
        PushSubscriptionResponse.serializer(),
        "/alerts/subscriptions",
        method = "POST",
        body = encode(PushSubscriptionCreate.serializer(), request),
    )

suspend fun ApiClient.updatePushSubscription(
    subscriptionId: String,
    request: PushSubscriptionUpdate,
): PushSubscriptionResponse =
    send(
        PushSubscriptionResponse.serializer(),
        "/alerts/subscriptions/$subscriptionId",
        method = "PATCH",
        body = encode(PushSubscriptionUpdate.serializer(), request),
    )

suspend fun ApiClient.deletePushSubscription(subscriptionId: String) =
    sendNoContent(
        "/alerts/subscriptions/$subscriptionId",
        "DELETE",
    )
