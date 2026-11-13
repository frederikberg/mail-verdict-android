package com.frederikberg.mailverdict.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
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
    @SerialName("is_glacier") val isGlacier: Boolean = false,
    @SerialName("mirrored_at") val mirroredAt: String,
    @SerialName("has_attachments") val hasAttachments: Boolean,
    @SerialName("verdict_is_spam") val verdictIsSpam: Boolean? = null,
    /** Which field tier matched; lower ranks higher. */
    @SerialName("match_tier") val matchTier: Int = 0,
)

@Serializable
data class SearchResponse(
    val query: String,
    val results: List<SearchResult>,
    val total: Int,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** A semantic hit; [tier] 1 is the confident group, 2 the "less likely" one. */
@Serializable
data class SemanticSearchHit(
    val id: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("folder_id") val folderId: String,
    @SerialName("thread_id") val threadId: String,
    val tier: Int,
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
    @SerialName("is_glacier") val isGlacier: Boolean = false,
    @SerialName("mirrored_at") val mirroredAt: String,
    @SerialName("has_attachments") val hasAttachments: Boolean,
    @SerialName("verdict_is_spam") val verdictIsSpam: Boolean? = null,
    @SerialName("match_tier") val matchTier: Int = 0,
)

@Serializable
data class SemanticSearchResponse(
    val query: String,
    val model: String,
    val results: List<SemanticSearchHit>,
)
