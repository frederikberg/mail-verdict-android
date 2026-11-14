package com.frederikberg.mailverdict.kit.actions

import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.contract.MessageSummary
import com.frederikberg.mailverdict.kit.maillist.MailListWindow

/** What a list shows, as far as projecting intents over it needs to know. */
data class ProjectionScope(
    /** The folders the list shows. A move into one keeps its row; a move anywhere else hides it. */
    val folderIds: Set<String>,
    val threaded: Boolean,
    /** The loaded window has more rows below or above it: a row put back past either end would claim a place the window has not loaded. */
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
)

/** Server data with intents applied on top: the one reducer every list and the reader read through, so no screen patches its own copy. */
object IntentProjection {
    /**
     * Whether [intent] applies over data read when the queue stood at [baseSequence]: while outstanding
     * and not undone; once done, only over a read that began before the server had it, since a later
     * read already carries it; never once refused.
     */
    fun applies(
        intent: MailIntent,
        baseSequence: Int,
    ): Boolean =
        when (intent.state) {
            IntentPhase.Pending, IntentPhase.Sending -> !intent.undoRequested
            IntentPhase.Done -> (intent.settledSequence ?: Int.MAX_VALUE) > baseSequence
            IntentPhase.Failed -> false
        }

    fun rows(
        base: List<MessageSummary>,
        intents: List<MailIntent>,
        baseSequence: Int,
        scope: ProjectionScope,
    ): List<MessageSummary> =
        intents.fold(base) { rows, intent -> if (applies(intent, baseSequence)) apply(intent, rows, scope) else rows }

    fun message(
        message: MessageDetail,
        intents: List<MailIntent>,
        baseSequence: Int,
    ): MessageDetail {
        var result = message
        for (intent in intents) {
            if (!applies(intent, baseSequence) || message.id !in intent.messageIds) continue
            result =
                when (intent.action) {
                    TriageAction.MarkRead -> result.copy(isSeen = true)
                    TriageAction.MarkUnread -> result.copy(isSeen = false)
                    TriageAction.Flag -> result.copy(isFlagged = true)
                    TriageAction.Unflag -> result.copy(isFlagged = false)
                    else -> result
                }
        }
        return result
    }

    private fun apply(
        intent: MailIntent,
        rows: List<MessageSummary>,
        scope: ProjectionScope,
    ): List<MessageSummary> {
        val ids = intent.messageIds.toSet()
        if (!intent.leavesFolder) {
            return rows.map { if (it.id in ids) applying(intent.action, it, scope.threaded) else it }
        }
        val target = intent.targetFolderId
        if (intent.action != TriageAction.Move || target == null || target !in scope.folderIds) {
            return rows.filterNot { it.id in ids }
        }
        val result = rows.map { if (it.id in ids) it.copy(folderId = target) else it }.toMutableList()
        for (snapshot in intent.snapshots) {
            if (snapshot.id !in ids || result.any { it.id == snapshot.id }) continue
            val row = snapshot.copy(folderId = target)
            val position =
                result.indexOfFirst { MailListWindow.sitsAbove(row, it) }.let {
                    if (it <
                        0
                    ) {
                        result.size
                    } else {
                        it
                    }
                }
            if (position == result.size && scope.hasOlder) continue
            if (position == 0 && scope.hasNewer) continue
            result.add(position, row)
        }
        return result
    }

    /** Read and star changes on one row. Grouped by conversation, the row's unread count moves with its own message: idempotent, so applying it over a read that already carries it changes nothing. */
    fun applying(
        action: TriageAction,
        row: MessageSummary,
        threaded: Boolean,
    ): MessageSummary =
        when (action) {
            TriageAction.MarkRead -> {
                val unread =
                    if (threaded) {
                        ((row.unreadInThread ?: 0) - if (row.isSeen) 0 else 1).coerceAtLeast(
                            0,
                        )
                    } else {
                        row.unreadInThread
                    }
                row.copy(isSeen = true, unreadInThread = unread)
            }
            TriageAction.MarkUnread -> {
                val unread = if (threaded) (row.unreadInThread ?: 0) + if (row.isSeen) 1 else 0 else row.unreadInThread
                row.copy(isSeen = false, unreadInThread = unread)
            }
            TriageAction.Flag -> row.copy(isFlagged = true)
            TriageAction.Unflag -> row.copy(isFlagged = false)
            else -> row
        }
}
