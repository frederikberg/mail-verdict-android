package com.frederikberg.mailverdict.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One write PostIMAP gave up on, a send that never left included. [action] names what was attempted
 * (`flag_add`, `move`, `send`, ...) and is kept as the raw string: an action this app has no label for
 * still shows. [detail] carries the attempt's own context, the message subject among it.
 */
@Serializable
data class NotificationResponse(
    val id: Int,
    @SerialName("account_id") val accountId: String,
    val action: String,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("folder_id") val folderId: String? = null,
    @SerialName("outbox_id") val outboxId: String? = null,
    val error: String? = null,
    val detail: JsonObject? = null,
    @SerialName("acknowledged_at") val acknowledgedAt: String? = null,
    @SerialName("reverted_at") val revertedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
)
