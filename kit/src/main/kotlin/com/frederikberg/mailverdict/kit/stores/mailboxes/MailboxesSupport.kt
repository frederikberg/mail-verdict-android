package com.frederikberg.mailverdict.kit.stores.mailboxes

import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.FolderOrderItem
import com.frederikberg.mailverdict.contract.UnifiedFolderResponse
import com.frederikberg.mailverdict.kit.live.LiveInvalidation

/** What a batch of live invalidations asks the overview to re-read. Plans from successive batches merge, so a reload already running costs at most one more. */
data class MailboxesReloadPlan(
    /** Accounts, unified views, the dead outbox, and every account's sync status and folders. */
    val everything: Boolean = false,
    /** Folder rows of [countAccountIds] (every account when [countsForAllAccounts]) and the unified rows. */
    val counts: Boolean = false,
    val countAccountIds: Set<String> = emptySet(),
    val countsForAllAccounts: Boolean = false,
    val deadOutbox: Boolean = false,
) {
    val isEmpty: Boolean get() = this == MailboxesReloadPlan()

    fun merged(other: MailboxesReloadPlan) =
        MailboxesReloadPlan(
            everything || other.everything,
            counts || other.counts,
            countAccountIds + other.countAccountIds,
            countsForAllAccounts || other.countsForAllAccounts,
            deadOutbox || other.deadOutbox,
        )
}

/** The rules behind the overview that need no network: ordering, badges, what a live event makes stale. */
object MailboxesSupport {
    const val UNIFIED_KEY = "unified"

    fun accountKey(accountId: String) = "account:$accountId"

    /** The fixed sequence special-use folders lead with, as the web's sidebar sorts them. */
    private val leadOrder = listOf("inbox", "drafts", "sent", "archive", "junk", "trash")

    /**
     * Visible folders in the order the server resolved, with the inbox forced to lead. When nothing
     * was ever saved the server answers plain alphabetical, so that case gets the full lead sequence
     * ([leadOrdered]) instead of only the inbox promotion, which would override a folder the person
     * genuinely dragged elsewhere.
     */
    fun orderFolders(
        items: List<FolderOrderItem>,
        hasCustomOrder: Boolean,
    ): List<FolderOrderItem> {
        val visible = items.filter { it.isVisible }
        if (!hasCustomOrder) return leadOrdered(visible, FolderOrderItem::specialUse, FolderOrderItem::imapName)
        val inbox = visible.indexOfFirst { it.specialUse == "inbox" }
        if (inbox <= 0) return visible
        return listOf(visible[inbox]) + visible.filterIndexed { index, _ -> index != inbox }
    }

    /** Every special-use folder first in the lead sequence (an unknown role after the named six), then the rest alphabetically by server name. */
    fun <F> leadOrdered(
        folders: List<F>,
        specialUse: (F) -> String?,
        imapName: (F) -> String,
    ): List<F> {
        val special =
            folders
                .filter { specialUse(it) != null }
                .sortedBy { leadOrder.indexOf(specialUse(it)).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        val regular =
            folders.filter { specialUse(it) == null }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, imapName))
        return special + regular
    }

    /** Drafts never carry an unread state, so the total stands in for it. */
    fun folderBadgeCount(
        specialUse: String?,
        unreadCount: Int,
        totalCount: Int,
    ): Int = if (specialUse == "drafts") totalCount else unreadCount

    /** Which accounts feed a merged unified view, in first-seen order, each once. */
    fun contributingAccounts(
        view: UnifiedFolderResponse,
        accounts: List<AccountResponse>,
    ): List<AccountResponse> =
        view.folders
            .map { it.accountId }
            .distinct()
            .mapNotNull { id -> accounts.firstOrNull { it.id == id } }

    fun distinctAccountNames(
        accountIds: List<String>,
        nameOf: (String) -> String?,
    ): List<String> = accountIds.distinct().mapNotNull(nameOf)

    /** The banner naming messages that could not be sent, or null when there are none. */
    fun deadOutboxBannerText(
        messageCount: Int,
        accountNames: List<String>,
    ): String? {
        if (messageCount <= 0) return null
        val plural = if (messageCount > 1) "s" else ""
        val target = if (accountNames.isEmpty()) "this account" else accountNames.joinToString(", ")
        return "$messageCount message$plural could not be sent — check SMTP settings on $target."
    }

    /**
     * What a batch of live invalidations makes stale on this screen, and nothing more. A mail event
     * moves counts only: the folder rows of its account and the unified rows. [accountOfFolder] names
     * the account a folder-only event belongs to; a folder it does not know widens the reload to every
     * account.
     */
    fun reloadPlan(
        invalidations: List<LiveInvalidation>,
        accountOfFolder: (String) -> String?,
    ): MailboxesReloadPlan {
        var plan = MailboxesReloadPlan()

        fun countsChanged(
            accountId: String?,
            folderId: String?,
        ) {
            plan = plan.copy(counts = true)
            val account = accountId ?: folderId?.let(accountOfFolder)
            plan =
                if (account != null) {
                    plan.copy(countAccountIds = plan.countAccountIds + account)
                } else {
                    plan.copy(countsForAllAccounts = true)
                }
        }
        for (invalidation in invalidations) {
            when (invalidation) {
                LiveInvalidation.Resync, LiveInvalidation.AccountsChanged, LiveInvalidation.FoldersChanged ->
                    plan = plan.copy(everything = true)
                is LiveInvalidation.MailNew -> countsChanged(invalidation.accountId, invalidation.folderId)
                is LiveInvalidation.MailDeleted -> countsChanged(invalidation.accountId, invalidation.folderId)
                // The payload carries no account; the folder names it.
                is LiveInvalidation.FolderSynced -> countsChanged(null, invalidation.folderId)
                is LiveInvalidation.MailUpdated ->
                    if (LiveInvalidation.messageUpdateMovesCounts(invalidation.changed)) {
                        countsChanged(invalidation.accountId, invalidation.folderId)
                    }
                is LiveInvalidation.OutboxUpdated -> plan = plan.copy(deadOutbox = true)
                else -> Unit
            }
        }
        return plan
    }
}
