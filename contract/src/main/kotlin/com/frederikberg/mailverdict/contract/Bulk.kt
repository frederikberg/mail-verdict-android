package com.frederikberg.mailverdict.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** "Every message of this folder as of [snapshotAt]", minus [excludeIds]. */
@Serializable
data class BulkActionScope(
    @SerialName("folder_id") val folderId: String,
    @SerialName("snapshot_at") val snapshotAt: String,
    val filter: BulkScopeFilter? = null,
    @SerialName("exclude_ids") val excludeIds: List<String> = emptyList(),
)

/** Names its targets either by [ids] or by a [scope]; the server accepts exactly one. */
@Serializable
data class BulkActionRequest(
    val action: BulkActionKind,
    val ids: List<String>? = null,
    val scope: BulkActionScope? = null,
    @SerialName("target_folder_id") val targetFolderId: String? = null,
    /** Per message, the folder the caller believes it is in. */
    @SerialName("expected_folder_ids") val expectedFolderIds: Map<String, String>? = null,
    @SerialName("expand_threads") val expandThreads: Boolean = false,
    @SerialName("expand_threads_through") val expandThreadsThrough: String? = null,
    @SerialName("confirm_message_count") val confirmMessageCount: Int? = null,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    val confirm: Boolean = false,
)

@Serializable
data class BulkActionSource(
    val id: String,
    @SerialName("folder_id") val folderId: String,
)

@Serializable
data class BulkActionResponse(
    val action: String,
    val success: Boolean,
    @SerialName("affected_count") val affectedCount: Int,
    @SerialName("duplicate_count") val duplicateCount: Int = 0,
    val errors: List<String> = emptyList(),
    @SerialName("skipped_ids") val skippedIds: List<String> = emptyList(),
    val sources: List<BulkActionSource> = emptyList(),
    @SerialName("target_folder_id") val targetFolderId: String? = null,
)

@Serializable
data class SelectionSnapshotResponse(
    val count: Int,
    @SerialName("snapshot_at") val snapshotAt: String,
)
