package com.frederikberg.mailverdict.kit.actions

import com.frederikberg.mailverdict.contract.BulkActionSource
import com.frederikberg.mailverdict.contract.MessageSummary
import kotlinx.serialization.Serializable

/** What a person can do to a message that changes where it is or how it is marked. */
enum class TriageAction(
    val raw: String,
    /** "Could not <phrase>" and the like. */
    val phrase: String,
    /** The message leaves the folder it was in. */
    val removesFromList: Boolean,
) {
    MarkRead("mark_read", "mark as read", false),
    MarkUnread("mark_unread", "mark as unread", false),
    Flag("flag", "star", false),
    Unflag("unflag", "unstar", false),
    Move("move", "move", true),
    Archive("archive", "archive", true),
    Trash("trash", "delete", true),
    Expunge("expunge", "delete forever", true),
    Spam("spam", "move to junk", true),
    NotSpam("not_spam", "mark as not junk", true),
    ;

    /** The action that puts the mark back; moves are reversed by moving back, never by an inverse. */
    val inverse: TriageAction?
        get() =
            when (this) {
                MarkRead -> MarkUnread
                MarkUnread -> MarkRead
                Flag -> Unflag
                Unflag -> Flag
                else -> null
            }
}

/** How an intent reaches the server. */
@Serializable
sealed interface Delivery {
    /** `POST /messages/{id}/action` for the one message named. */
    @Serializable
    data object Message : Delivery

    /** `POST /accounts/{id}/messages/bulk-action` with the ids named; [expandThreads] when each id stands for a conversation row. */
    @Serializable
    data class Bulk(
        val expandThreads: Boolean,
    ) : Delivery
}

@Serializable
enum class IntentPhase {
    /** Waiting its turn, for the network or for a retry. */
    Pending,

    /** The request is out. */
    Sending,

    /** The server accepted it. */
    Done,

    /** The server refused it, or it kept failing. Kept, unapplied, until retried or discarded. */
    Failed,
}

/**
 * One action a person took, held until the server has it and persisted so it survives a restart.
 * What a screen shows is the server's data with every intent still outstanding applied on top
 * ([IntentProjection]), so a read landing while a request is out can never undo on screen what the
 * person just did.
 */
@Serializable
data class MailIntent(
    /** Also the request's idempotency key: every retry, a restart's included, is the same request to the server. */
    val id: String,
    val accountId: String,
    val action: TriageAction,
    val targetFolderId: String? = null,
    val messageIds: List<String>,
    val delivery: Delivery,
    /** The folder each message was in when the action was taken: where Undo moves it back to. */
    val originFolderIds: Map<String, String> = emptyMap(),
    /** The list rows the messages had: an undone move puts these back. */
    val snapshots: List<MessageSummary> = emptyList(),
    /** The intent this one undoes. */
    val undoes: String? = null,
    /** Only ever read server-side for a permanent delete of a message already in the glacier; sent with every expunge the person confirmed. */
    val confirm: Boolean = false,
    val createdAtMs: Long,
    val state: IntentPhase = IntentPhase.Pending,
    val attempts: Int = 0,
    val nextAttemptAtMs: Long? = null,
    val lastError: String? = null,
    /** When the latest request left, for telling its echo over the event stream from another client's change. */
    val sentAtMs: Long? = null,
    /** The queue's sequence when this became done or failed. */
    val settledSequence: Int? = null,
    val settledAtMs: Long? = null,
    /** Every message the server reports having moved: an expanded conversation names more than [messageIds] does. */
    val movedSources: List<BulkActionSource> = emptyList(),
    /** An earlier attempt may have reached the server without an answer coming back: the messages themselves are looked at before sending again. */
    val mayHaveLanded: Boolean = false,
    /** Undone while its request was out: it no longer applies, and is reversed once the request settles. */
    val undoRequested: Boolean = false,
    /** Where the server filed each message: the folder an undo expects to find it in. */
    val filedFolderIds: Map<String, String> = emptyMap(),
    /** For a bulk action expanding conversations: the newest `mirrored_at` among the rows acted on, so replies that arrive before a late send are not swept along. */
    val seenThrough: String? = null,
    /** On an intent that undoes a move: the action it reverses and that action's own target. */
    val reversedAction: TriageAction? = null,
    val reversedTargetFolderId: String? = null,
) {
    val isOpen: Boolean get() = state == IntentPhase.Pending || state == IntentPhase.Sending
    val leavesFolder: Boolean get() = action.removesFromList

    /** The folder each message is expected in when sent, for actions that must not follow a message somewhere it has been filed since. Read and star changes follow it anywhere. */
    val expectedFolderIds: Map<String, String>?
        get() {
            if (!leavesFolder || originFolderIds.isEmpty()) return null
            return originFolderIds.filterKeys { it in messageIds }.takeIf { it.isNotEmpty() }
        }
}

/** What a surface asks the queue to do: an intent before it has an identity or a state. */
data class IntentRequest(
    val accountId: String,
    val action: TriageAction,
    val messageIds: List<String>,
    val targetFolderId: String? = null,
    val delivery: Delivery = Delivery.Message,
    val originFolderIds: Map<String, String> = emptyMap(),
    val snapshots: List<MessageSummary> = emptyList(),
    val seenThrough: String? = null,
    val confirm: Boolean = false,
)

/** How an intent ended, for a caller that waits on it. */
sealed interface IntentOutcome {
    data class Done(
        val affectedCount: Int?,
        val sources: List<BulkActionSource>,
    ) : IntentOutcome

    /** The message no longer exists on the server. */
    data object Gone : IntentOutcome

    data class Failed(
        val reason: String,
    ) : IntentOutcome

    /** Undone or discarded before the server had it. */
    data object Cancelled : IntentOutcome

    /** The messages were no longer where the action expected them; nothing was written. */
    data object NotApplied : IntentOutcome
}

/** What a row or the reader shows for the intents naming a message. */
enum class IntentRowState { None, Waiting, Failed }

/** Something the person is told about: an Undo offer, or a failure with Retry. */
sealed interface QueueNotice {
    val message: String

    data class Undo(
        override val message: String,
        val ids: List<String>,
    ) : QueueNotice

    data class Failure(
        override val message: String,
        val id: String,
    ) : QueueNotice

    data class Info(
        override val message: String,
    ) : QueueNotice
}
