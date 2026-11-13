package com.frederikberg.mailverdict.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TagResponse(
    @SerialName("tag_name") val tagName: String,
    val source: String,
)

@Serializable
data class AttachmentSummary(
    val id: String,
    val filename: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)

@Serializable
data class VerdictResponse(
    val id: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("is_spam") val isSpam: Boolean,
    @SerialName("model_used") val modelUsed: String? = null,
    val reasoning: String? = null,
    val source: String,
    @SerialName("created_at") val createdAt: String,
)

/**
 * One row of a message list. [SearchResult] and [SemanticSearchHit] repeat the same leading fields
 * instead of nesting this type: the server's own schemas are flat, and a client decoding them has
 * to match the wire.
 */
@Serializable
data class MessageSummary(
    val id: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("folder_id") val folderId: String,
    @SerialName("thread_id") val threadId: String,
    val subject: String? = null,
    @SerialName("from_addr") val fromAddr: String? = null,
    @SerialName("to_addrs") val toAddrs: AddressList? = null,
    @SerialName("received_at") val receivedAt: String? = null,
    @SerialName("is_seen") val isSeen: Boolean = false,
    @SerialName("is_flagged") val isFlagged: Boolean = false,
    @SerialName("is_answered") val isAnswered: Boolean = false,
    @SerialName("is_draft") val isDraft: Boolean = false,
    val snippet: String? = null,
    @SerialName("pending_sync") val pendingSync: Boolean = false,
    @SerialName("is_truncated") val isTruncated: Boolean = false,
    @SerialName("thread_count") val threadCount: Int? = null,
    @SerialName("unread_in_thread") val unreadInThread: Int? = null,
    /** In the account's glacier: no longer on the mail server. */
    @SerialName("is_glacier") val isGlacier: Boolean = false,
    @SerialName("mirrored_at") val mirroredAt: String,
    @SerialName("has_attachments") val hasAttachments: Boolean,
    /** The latest spam verdict; null when the message was never classified. */
    @SerialName("verdict_is_spam") val verdictIsSpam: Boolean? = null,
)

@Serializable
data class MessageListResponse(
    val messages: List<MessageSummary>,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("has_more_newer") val hasMoreNewer: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("prev_cursor") val prevCursor: String? = null,
    /** The server's clock when it answered; a list window is only comparable to others by it. */
    @SerialName("as_of") val asOf: String? = null,
)

@Serializable
data class MessageDetail(
    val id: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("folder_id") val folderId: String,
    @SerialName("thread_id") val threadId: String,
    @SerialName("message_id") val messageId: String? = null,
    val subject: String? = null,
    @SerialName("from_addr") val fromAddr: String? = null,
    @SerialName("to_addrs") val toAddrs: AddressList? = null,
    @SerialName("cc_addrs") val ccAddrs: AddressList? = null,
    @SerialName("bcc_addrs") val bccAddrs: AddressList? = null,
    @SerialName("reply_to") val replyTo: String? = null,
    @SerialName("envelope_sender") val envelopeSender: String? = null,
    @SerialName("in_reply_to") val inReplyTo: String? = null,
    val references: List<String>? = null,
    @SerialName("received_at") val receivedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("body_text") val bodyText: String? = null,
    @SerialName("body_html") val bodyHtml: String? = null,
    val snippet: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("is_seen") val isSeen: Boolean = false,
    @SerialName("is_flagged") val isFlagged: Boolean = false,
    @SerialName("is_answered") val isAnswered: Boolean = false,
    @SerialName("is_draft") val isDraft: Boolean = false,
    @SerialName("pending_sync") val pendingSync: Boolean = false,
    @SerialName("is_truncated") val isTruncated: Boolean = false,
    @SerialName("is_glacier") val isGlacier: Boolean = false,
    /** For a glaciered message, the folder it was taken from. */
    @SerialName("origin_folder_name") val originFolderName: String? = null,
    /** The HTML body carries remote images the server blocked. */
    @SerialName("has_blocked_images") val hasBlockedImages: Boolean = false,
    /** The sender is on the image allow list, so nothing was blocked. */
    @SerialName("images_allowed") val imagesAllowed: Boolean = false,
    val keywords: List<String> = emptyList(),
    val tags: List<TagResponse> = emptyList(),
    val attachments: List<AttachmentSummary> = emptyList(),
    val verdict: VerdictResponse? = null,
)

@Serializable
data class ThreadResponse(
    val messages: List<MessageDetail>,
)

@Serializable
data class MessageQuoteResponse(
    val html: String,
)

@Serializable
data class MessageActionRequest(
    val action: MessageActionKind,
    @SerialName("target_folder_id") val targetFolderId: String? = null,
    /** The folder the caller believes the message is in; the server refuses the action if it moved. */
    @SerialName("expected_folder_id") val expectedFolderId: String? = null,
    val keyword: String? = null,
    /** Makes a retry of this exact action answer the first attempt's result instead of acting twice. */
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    val confirm: Boolean = false,
)

@Serializable
data class MessageActionResponse(
    @SerialName("message_id") val messageId: String,
    val action: String,
    val success: Boolean,
    val applied: Boolean = true,
    @SerialName("folder_id") val folderId: String? = null,
    val message: String? = null,
)

@Serializable
data class FeedbackRequest(
    @SerialName("is_spam") val isSpam: Boolean,
)

@Serializable
data class FeedbackResponse(
    @SerialName("message_id") val messageId: String,
    @SerialName("is_spam") val isSpam: Boolean,
    val success: Boolean,
    val message: String? = null,
)
