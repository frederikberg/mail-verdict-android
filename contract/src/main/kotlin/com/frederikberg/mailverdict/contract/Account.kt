package com.frederikberg.mailverdict.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccountResponse(
    val id: String,
    val name: String,
    @SerialName("imap_host") val imapHost: String,
    @SerialName("imap_port") val imapPort: Int,
    @SerialName("imap_user") val imapUser: String,
    @SerialName("smtp_host") val smtpHost: String? = null,
    @SerialName("smtp_port") val smtpPort: Int? = null,
    @SerialName("smtp_user") val smtpUser: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    val state: String = "created",
    @SerialName("state_error") val stateError: String? = null,
    val emoji: String? = null,
    @SerialName("folder_order") val folderOrder: List<String>? = null,
    @SerialName("spam_enabled") val spamEnabled: Boolean = false,
    @SerialName("orders_enabled") val ordersEnabled: Boolean = false,
    @SerialName("junk_retention_days") val junkRetentionDays: Int? = null,
    @SerialName("trash_retention_days") val trashRetentionDays: Int? = null,
    @SerialName("glacier_enabled") val glacierEnabled: Boolean = false,
    @SerialName("glacier_auto_days") val glacierAutoDays: Int? = null,
    @SerialName("glacier_folder_id") val glacierFolderId: String? = null,
    @SerialName("glacier_sweep_last_refusal") val glacierSweepLastRefusal: String? = null,
    val capabilities: Map<String, Boolean>? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class SyncStatusResponse(
    @SerialName("account_id") val accountId: String,
    val state: String,
    @SerialName("state_error") val stateError: String? = null,
    @SerialName("sync_tier") val syncTier: String? = null,
    @SerialName("folders_total") val foldersTotal: Int = 0,
    @SerialName("folders_synced") val foldersSynced: Int = 0,
    @SerialName("messages_synced") val messagesSynced: Int = 0,
    @SerialName("error_count") val errorCount: Int = 0,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_full_sync") val lastFullSync: String? = null,
    @SerialName("last_incr_sync") val lastIncrSync: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class FolderResponse(
    val id: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("imap_name") val imapName: String,
    @SerialName("display_name") val displayName: String? = null,
    /** `imap` for a real mailbox, `glacier` for the one synthetic folder of an account's glacier. */
    val kind: FolderKind = FolderKind.Imap,
    @SerialName("special_use") val specialUse: String? = null,
    @SerialName("mailbox_id") val mailboxId: String? = null,
    @SerialName("is_visible") val isVisible: Boolean = true,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("initial_sync_done") val initialSyncDone: Boolean = false,
    @SerialName("backfill_total") val backfillTotal: Int? = null,
    @SerialName("idle_requested") val idleRequested: Boolean = false,
    @SerialName("idle_status") val idleStatus: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
    @SerialName("sync_error") val syncError: String? = null,
    @SerialName("unified_view_ids") val unifiedViewIds: List<String> = emptyList(),
    /** Whether mail arriving here is classified and embedded: the folder's role and its own switch combined. */
    @SerialName("pipeline_enabled") val pipelineEnabled: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
)

/** One folder in `GET /accounts/{id}/folder-order`: the account's visible-or-hidden folders in the order the person saved. */
@Serializable
data class FolderOrderItem(
    @SerialName("folder_id") val folderId: String,
    @SerialName("imap_name") val imapName: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("special_use") val specialUse: String? = null,
    @SerialName("is_visible") val isVisible: Boolean = true,
    val kind: FolderKind = FolderKind.Imap,
    @SerialName("pipeline_enabled") val pipelineEnabled: Boolean = true,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
)

/** `PATCH /folders/{id}/prefs`: only the fields present are changed. */
@Serializable
data class FolderPrefsUpdate(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("is_visible") val isVisible: Boolean? = null,
    @SerialName("pipeline_enabled") val pipelineEnabled: Boolean? = null,
    @SerialName("real_time") val realTime: Boolean? = null,
    @SerialName("special_use_override") val specialUseOverride: String? = null,
    @SerialName("unified_view_ids") val unifiedViewIds: List<String>? = null,
)

@Serializable
data class FolderOrderResponse(
    val folders: List<FolderOrderItem>,
)

@Serializable
data class IdentityResponse(
    val id: String,
    @SerialName("account_id") val accountId: String,
    val address: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class UnifiedFolderSource(
    @SerialName("folder_id") val folderId: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("account_name") val accountName: String,
    @SerialName("account_emoji") val accountEmoji: String? = null,
    @SerialName("imap_name") val imapName: String,
    @SerialName("special_use") val specialUse: String? = null,
)

/** One unified view as the sidebar lists it, with its member folders and summed counts. */
@Serializable
data class UnifiedFolderResponse(
    val id: String,
    @SerialName("unified_name") val unifiedName: String,
    val emoji: String? = null,
    @SerialName("total_count") val totalCount: Int,
    @SerialName("unread_count") val unreadCount: Int,
    val folders: List<UnifiedFolderSource>,
)

@Serializable
data class UnifiedViewResponse(
    val id: String,
    val name: String,
    val emoji: String? = null,
    val position: Int,
)
