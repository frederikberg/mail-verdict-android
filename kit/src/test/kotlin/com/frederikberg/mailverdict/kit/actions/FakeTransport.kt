package com.frederikberg.mailverdict.kit.actions

import com.frederikberg.mailverdict.contract.BulkActionRequest
import com.frederikberg.mailverdict.contract.BulkActionResponse
import com.frederikberg.mailverdict.contract.FolderResponse
import com.frederikberg.mailverdict.contract.MessageActionResponse
import com.frederikberg.mailverdict.contract.ThreadResponse

/** Records every request and answers from scripts the test sets. */
class FakeTransport : IntentTransport {
    data class Sent(
        val kind: String,
        val action: String,
        val ids: List<String>,
        val key: String,
        val target: String?,
        val expected: Map<String, String>?,
        val confirm: Boolean,
        val expandThreads: Boolean = false,
        val seenThrough: String? = null,
    )

    val sent = mutableListOf<Sent>()
    var inFlight = 0
    var maxInFlight = 0

    /** Called for every single-message request; throw to fail it. */
    var onMessage: suspend (Sent) -> MessageActionResponse = { ok(it) }
    var onBulk: suspend (
        Sent,
    ) -> BulkActionResponse = { BulkActionResponse(it.action, true, it.ids.size, targetFolderId = it.target) }
    var states = mutableMapOf<String, MessageState?>()
    var stateReads = 0
    var folders =
        listOf(
            FolderResponse(id = "inbox1", accountId = "a1", imapName = "INBOX", specialUse = "inbox"),
            FolderResponse(id = "archive1", accountId = "a1", imapName = "Archive", specialUse = "archive"),
            FolderResponse(id = "trash1", accountId = "a1", imapName = "Trash", specialUse = "trash"),
            FolderResponse(id = "junk1", accountId = "a1", imapName = "Junk", specialUse = "junk"),
        )

    fun ok(sent: Sent) =
        MessageActionResponse(
            messageId = sent.ids.first(),
            action = sent.action,
            success = true,
            folderId =
                when (sent.action) {
                    "archive" -> "archive1"
                    "trash" -> "trash1"
                    "spam" -> "junk1"
                    "not_spam" -> "inbox1"
                    "move" -> sent.target
                    else -> null
                },
        )

    private suspend fun <T> tracked(block: suspend () -> T): T {
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            return block()
        } finally {
            inFlight--
        }
    }

    override suspend fun deliverMessageAction(
        messageId: String,
        action: TriageAction,
        targetFolderId: String?,
        expectedFolderId: String?,
        idempotencyKey: String,
        confirm: Boolean,
    ): MessageActionResponse {
        val request =
            Sent(
                "message",
                action.raw,
                listOf(messageId),
                idempotencyKey,
                targetFolderId,
                expectedFolderId?.let {
                    mapOf(
                        messageId to it,
                    )
                },
                confirm,
            )
        sent += request
        return tracked { onMessage(request) }
    }

    override suspend fun deliverBulkAction(
        accountId: String,
        request: BulkActionRequest,
    ): BulkActionResponse {
        val recorded =
            Sent(
                "bulk",
                request.action.raw,
                request.ids.orEmpty(),
                request.idempotencyKey.orEmpty(),
                request.targetFolderId,
                request.expectedFolderIds,
                request.confirm,
                request.expandThreads,
                request.expandThreadsThrough,
            )
        sent += recorded
        return tracked { onBulk(recorded) }
    }

    override suspend fun fetchMessageState(
        messageId: String,
        includeFlags: Boolean,
    ): MessageState? {
        stateReads++
        // A message nobody said anything about is still in the inbox, unread and unstarred.
        return if (messageId in states) states[messageId] else MessageState("inbox1", isSeen = false, isFlagged = false)
    }

    override suspend fun fetchFolders(accountId: String) = folders

    override suspend fun fetchThread(messageId: String) = ThreadResponse(emptyList())
}
