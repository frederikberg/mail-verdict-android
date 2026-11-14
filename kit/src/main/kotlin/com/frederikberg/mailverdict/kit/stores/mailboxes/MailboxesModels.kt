package com.frederikberg.mailverdict.kit.stores.mailboxes

import com.frederikberg.mailverdict.contract.FolderKind

/** Whether an account's `error` state is terminal or still retrying: one that synced at least once before erroring is still working through its retry loop. */
enum class AccountConnectionState {
    Ok,
    Retrying,
    NeverConnected,

    ;

    companion object {
        fun classify(
            state: String,
            hasFullSync: Boolean,
        ): AccountConnectionState =
            when {
                state != "error" -> Ok
                hasFullSync -> Retrying
                else -> NeverConnected
            }
    }
}

data class MailboxesFolderRow(
    val id: String,
    val accountId: String,
    val displayName: String,
    val specialUse: String?,
    /** The unread count, or the total for Drafts, which never carry an unread state. */
    val badgeCount: Int,
    val totalCount: Int,
    val kind: FolderKind = FolderKind.Imap,
) {
    val isGlacier: Boolean get() = kind == FolderKind.Glacier
}

data class MailboxesAccountSection(
    val id: String,
    val name: String,
    val emoji: String?,
    val connectionState: AccountConnectionState,
    val stateError: String?,
    val folders: List<MailboxesFolderRow>,
) {
    val collapseKey: String get() = MailboxesSupport.accountKey(id)
}

data class MailboxesUnifiedRow(
    val id: String,
    val name: String,
    val emoji: String?,
    val unreadCount: Int,
    val contributingAccountEmojis: List<String>,
)

/** Everything the overview screen draws. */
data class MailboxesState(
    val unifiedRows: List<MailboxesUnifiedRow> = emptyList(),
    val accountSections: List<MailboxesAccountSection> = emptyList(),
    val deadOutboxBanner: String? = null,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    /** False only once a load found the person has no account at all. */
    val hasAnyAccount: Boolean = true,
    /** Keys of the collapsed sections ([MailboxesSupport.UNIFIED_KEY], [MailboxesSupport.accountKey]). */
    val collapsedKeys: Set<String> = emptySet(),
) {
    fun isCollapsed(key: String): Boolean = key in collapsedKeys
}

/** Which sections are collapsed, kept across launches. */
interface CollapseStore {
    fun collapsedKeys(): Set<String>

    fun setCollapsed(
        key: String,
        collapsed: Boolean,
    )
}

class InMemoryCollapseStore(
    initial: Set<String> = emptySet(),
) : CollapseStore {
    private val keys = initial.toMutableSet()

    @Synchronized
    override fun collapsedKeys(): Set<String> = keys.toSet()

    @Synchronized
    override fun setCollapsed(
        key: String,
        collapsed: Boolean,
    ) {
        if (collapsed) keys += key else keys -= key
    }
}
