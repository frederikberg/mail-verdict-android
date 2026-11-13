package com.frederikberg.mailverdict.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry of the in-app alert list. Every field is always sent; the optional ones as null. */
@Serializable
data class AlertResponse(
    val id: String,
    val kind: String,
    val title: String? = null,
    val body: String? = null,
    val url: String? = null,
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("folder_id") val folderId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("dismissed_at") val dismissedAt: String? = null,
)

@Serializable
data class AlertUnseenCountResponse(
    val unseen: Int,
    @SerialName("by_kind") val byKind: Map<String, Int>,
)

@Serializable
data class AlertBadgeResponse(
    val count: Int,
)

@Serializable
data class VapidPublicKeyResponse(
    val available: Boolean,
    @SerialName("public_key") val publicKey: String? = null,
)

@Serializable
data class PushSubscriptionKeys(
    val auth: String,
    val p256dh: String,
)

@Serializable
data class PushSubscriptionCreate(
    val endpoint: String,
    val keys: PushSubscriptionKeys,
    val label: String? = null,
)

@Serializable
data class PushSubscriptionUpdate(
    val label: String? = null,
    @SerialName("muted_channels") val mutedChannels: List<PushChannel>? = null,
    @SerialName("reminders_enabled") val remindersEnabled: Boolean? = null,
    @SerialName("alert_folder_ids") val alertFolderIds: List<String>? = null,
)

@Serializable
data class PushSubscriptionResponse(
    val id: String,
    val transport: PushTransport,
    val label: String? = null,
    @SerialName("muted_channels") val mutedChannels: List<PushChannel>,
    @SerialName("reminders_enabled") val remindersEnabled: Boolean,
    @SerialName("alert_folder_ids") val alertFolderIds: List<String>? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("failed_at") val failedAt: String? = null,
)

/** Whether the server can reach native push relays at all, and which ones it trusts. */
@Serializable
data class NativePushConfigResponse(
    val available: Boolean,
    val reason: String? = null,
    @SerialName("relay_urls") val relayUrls: List<String>,
)
