package com.frederikberg.mailverdict.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The body of `POST /outbox`. The server reads it by hand (JSON or multipart), so it never reaches
 * the published schema and ContractTest cannot check it; this mirrors the route's own parser.
 */
@Serializable
data class OutboxCreateRequest(
    @SerialName("account_id") val accountId: String,
    /** `send` or `draft`. */
    val kind: String,
    val to: List<String> = emptyList(),
    val cc: List<String>? = null,
    val bcc: List<String>? = null,
    val subject: String? = null,
    @SerialName("body_text") val bodyText: String? = null,
    @SerialName("body_html") val bodyHtml: String? = null,
    @SerialName("in_reply_to") val inReplyTo: String? = null,
    val references: List<String>? = null,
    @SerialName("identity_id") val identityId: String? = null,
    @SerialName("replaces_message_id") val replacesMessageId: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
)

@Serializable
data class OutboxAttachmentSummary(
    val id: String,
    val filename: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)

@Serializable
data class OutboxResponse(
    val id: String,
    @SerialName("account_id") val accountId: String,
    val kind: String,
    val status: String,
    @SerialName("from_addr") val fromAddr: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String>? = null,
    val bcc: List<String>? = null,
    val subject: String? = null,
    val error: String? = null,
    val attachments: List<OutboxAttachmentSummary> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class PendingSendAttachmentSummary(
    val id: String,
    val filename: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("content_id") val contentId: String? = null,
)

/** A send held inside its undo window until [sendAfter]. */
@Serializable
data class PendingSendResponse(
    val id: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("send_after") val sendAfter: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("from_addr") val fromAddr: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String>? = null,
    val bcc: List<String>? = null,
    val subject: String? = null,
    @SerialName("body_html") val bodyHtml: String? = null,
    @SerialName("in_reply_to") val inReplyTo: String? = null,
    val references: List<String>? = null,
    @SerialName("replaces_message_id") val replacesMessageId: String? = null,
    val attachments: List<PendingSendAttachmentSummary> = emptyList(),
)
