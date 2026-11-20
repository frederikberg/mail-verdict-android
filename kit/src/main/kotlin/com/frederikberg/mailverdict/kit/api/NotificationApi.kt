package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.NotificationResponse
import com.frederikberg.mailverdict.kit.net.ApiClient
import kotlinx.serialization.builtins.ListSerializer

/**
 * The writes PostIMAP gave up on, across every account of the caller, newest first. Answers 501 while the
 * connected PostIMAP is older than 1.3.0.
 */
suspend fun ApiClient.listNotifications(
    unacknowledgedOnly: Boolean? = null,
    limit: Int? = null,
): List<NotificationResponse> =
    send(
        ListSerializer(NotificationResponse.serializer()),
        "/notifications",
        query = query("unacknowledged_only" to unacknowledgedOnly, "limit" to limit),
    )

/** Acknowledging is per account, and account-wide: every device sees the notification gone. */
suspend fun ApiClient.acknowledgeNotification(
    accountId: String,
    notificationId: Int,
) = sendNoContent("/accounts/$accountId/notifications/$notificationId/ack", "POST")

suspend fun ApiClient.acknowledgeAllNotifications(accountId: String) =
    sendNoContent("/accounts/$accountId/notifications/ack-all", "POST")
