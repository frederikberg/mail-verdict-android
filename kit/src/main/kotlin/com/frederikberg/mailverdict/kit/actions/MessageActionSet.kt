package com.frederikberg.mailverdict.kit.actions

import com.frederikberg.mailverdict.contract.FolderKind
import com.frederikberg.mailverdict.contract.FolderOrderResponse
import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.contract.MessageSummary
import com.frederikberg.mailverdict.kit.format.folderDisplayName

/** One action of the menus, the swipes and the reader: the same wording and rules wherever it appears. */
enum class UiAction(
    val title: String,
) {
    MarkRead("Mark as read"),
    MarkUnread("Mark as unread"),
    Star("Star"),
    Unstar("Unstar"),
    MoveTo("Move to…"),
    MoveToJunk("Move to Junk"),
    NotJunk("Not junk"),
    Archive("Archive"),
    ConfirmVerdict("Confirm verdict"),
    CorrectVerdict("Correct verdict"),
    Delete("Delete"),
    DeleteForever("Delete forever"),
    ;

    val isDestructive: Boolean get() = this == Delete || this == DeleteForever

    /** The queue action this sends, or null for the ones that are not a queued mail action. */
    val action: TriageAction?
        get() =
            when (this) {
                MarkRead -> TriageAction.MarkRead
                MarkUnread -> TriageAction.MarkUnread
                Star -> TriageAction.Flag
                Unstar -> TriageAction.Unflag
                Archive -> TriageAction.Archive
                MoveToJunk -> TriageAction.Spam
                NotJunk -> TriageAction.NotSpam
                Delete -> TriageAction.Trash
                DeleteForever -> TriageAction.Expunge
                MoveTo -> TriageAction.Move
                ConfirmVerdict, CorrectVerdict -> null
            }
}

/** What the set needs to decide which actions apply: state only, no identifiers. */
data class ActionContext(
    val isRead: Boolean,
    val isStarred: Boolean,
    val isInTrash: Boolean,
    val isInJunk: Boolean,
    /** In the account's glacier: no longer on the mail server, so the only copy. */
    val isInGlacier: Boolean = false,
    /** The spam filter's verdict, when the message carries one; offered only where one message is shown. */
    val verdictIsSpam: Boolean? = null,
)

data class ActionGroup(
    val kind: Kind,
    val actions: List<UiAction>,
) {
    enum class Kind { Verdict, State, Destructive }
}

/** The one list every surface renders from, so the selection bar, the swipes and the reader's menu never drift into different sets for the same message. */
object MessageActionSet {
    fun groups(context: ActionContext): List<ActionGroup> {
        val groups = mutableListOf<ActionGroup>()
        if (context.verdictIsSpam != null) {
            groups += ActionGroup(ActionGroup.Kind.Verdict, listOf(UiAction.ConfirmVerdict, UiAction.CorrectVerdict))
        }
        val state =
            mutableListOf(
                if (context.isRead) UiAction.MarkUnread else UiAction.MarkRead,
                if (context.isStarred) UiAction.Unstar else UiAction.Star,
                UiAction.MoveTo,
            )
        // A spam ruling on a message already in the glacier is not supported by the server.
        if (!context.isInGlacier) state += if (context.isInJunk) UiAction.NotJunk else UiAction.MoveToJunk
        state += UiAction.Archive
        groups += ActionGroup(ActionGroup.Kind.State, state)
        groups += ActionGroup(ActionGroup.Kind.Destructive, listOf(destructive(context)))
        return groups
    }

    /** Delete moves to Trash; in Trash or the glacier it is the only copy and the action is permanent. */
    fun destructive(context: ActionContext): UiAction =
        if (context.isInTrash || context.isInGlacier) UiAction.DeleteForever else UiAction.Delete

    /** The swipes: right archives, left deletes. */
    fun swipeEnd(context: ActionContext): UiAction = UiAction.Archive

    fun swipeStart(context: ActionContext): UiAction = destructive(context)
}

/** Builds the queue's requests from what a screen holds. */
object TriageRequests {
    /**
     * One request per account among [rows]. A row of a grouped list stands for a conversation, so its
     * request expands it ([Delivery.Bulk] with `expandThreads`) and carries the newest `mirrored_at`
     * among the rows, so replies that arrive before a late send are not swept along.
     */
    fun forRows(
        rows: List<MessageSummary>,
        action: UiAction,
        threaded: Boolean,
        targetFolderId: String? = null,
    ): List<IntentRequest> {
        val kind = action.action ?: return emptyList()
        if (kind == TriageAction.Move && targetFolderId == null) return emptyList()
        return rows.groupBy { it.accountId }.map { (accountId, accountRows) ->
            val single = accountRows.size == 1 && !threaded
            IntentRequest(
                accountId = accountId,
                action = kind,
                messageIds = accountRows.map { it.id },
                targetFolderId = targetFolderId.takeIf { kind == TriageAction.Move },
                delivery = if (single) Delivery.Message else Delivery.Bulk(expandThreads = threaded),
                originFolderIds = accountRows.associate { it.id to it.folderId },
                snapshots = accountRows,
                seenThrough = if (threaded) accountRows.mapNotNull { it.mirroredAt }.maxOrNull() else null,
                confirm = kind == TriageAction.Expunge,
            )
        }
    }

    fun forMessage(
        message: MessageDetail,
        action: UiAction,
        targetFolderId: String? = null,
    ): IntentRequest? {
        val kind = action.action ?: return null
        if (kind == TriageAction.Move && targetFolderId == null) return null
        return IntentRequest(
            accountId = message.accountId,
            action = kind,
            messageIds = listOf(message.id),
            targetFolderId = targetFolderId.takeIf { kind == TriageAction.Move },
            originFolderIds = mapOf(message.id to message.folderId),
            confirm = kind == TriageAction.Expunge,
        )
    }

    /** What the Undo offer says; null for the one action that cannot be undone. */
    fun undoNotice(
        action: TriageAction,
        count: Int,
    ): String? {
        val base =
            when (action) {
                TriageAction.Archive -> "Archived"
                TriageAction.Trash -> "Moved to Trash"
                TriageAction.Spam -> "Moved to Junk"
                TriageAction.NotSpam -> "Moved out of Junk"
                TriageAction.Move -> "Moved"
                TriageAction.MarkRead -> "Marked as read"
                TriageAction.MarkUnread -> "Marked as unread"
                TriageAction.Flag -> "Starred"
                TriageAction.Unflag -> "Unstarred"
                TriageAction.Expunge -> return null
            }
        return if (count > 1) "$base · $count messages" else base
    }
}

/** One row of the Move picker: a folder of one account. */
data class MoveTarget(
    val accountId: String,
    val folderId: String,
    val name: String,
    val specialUse: String?,
)

object MovePicker {
    /** The folders a message can be moved to. The glacier is not offered: a move into it takes the message off the mail server for good and has no undo. */
    fun targets(
        accountId: String,
        order: FolderOrderResponse,
    ): List<MoveTarget> =
        order.folders
            .filter { it.kind != FolderKind.Glacier }
            .map {
                MoveTarget(
                    accountId,
                    it.folderId,
                    folderDisplayName(it.imapName, it.displayName, it.specialUse),
                    it.specialUse,
                )
            }

    /**
     * Filtered by name as the field is typed, otherwise the most recently used targets first and then
     * everything else in folder order. A folder a message is already in is never offered: moving there
     * does nothing.
     */
    fun ordered(
        targets: List<MoveTarget>,
        excludingFolderIds: Set<String>,
        recentIds: List<String>,
        query: String,
    ): List<MoveTarget> {
        val candidates = targets.filter { it.folderId !in excludingFolderIds }
        val needle = query.trim().lowercase()
        if (needle.isNotEmpty()) return candidates.filter { needle in it.name.lowercase() }
        val byId = candidates.associateBy { it.folderId }
        val recent = recentIds.mapNotNull { byId[it] }
        val recentSet = recent.mapTo(HashSet()) { it.folderId }
        return recent + candidates.filter { it.folderId !in recentSet }
    }
}

/** The five most recently used move targets per account. */
interface RecentMoveTargets {
    fun recentIds(accountId: String): List<String>

    fun record(
        accountId: String,
        folderId: String,
    )

    companion object {
        const val MAX = 5
    }
}

class InMemoryRecentMoveTargets : RecentMoveTargets {
    private val byAccount = HashMap<String, List<String>>()

    override fun recentIds(accountId: String) = byAccount[accountId].orEmpty()

    override fun record(
        accountId: String,
        folderId: String,
    ) {
        byAccount[accountId] =
            (listOf(folderId) + recentIds(accountId).filter { it != folderId }).take(RecentMoveTargets.MAX)
    }
}
