package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.AddressList
import com.frederikberg.mailverdict.contract.AttachmentSummary
import com.frederikberg.mailverdict.contract.ContactSearchHitOut
import com.frederikberg.mailverdict.contract.IdentityResponse
import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.contract.OutboxCreateRequest
import com.frederikberg.mailverdict.contract.OutboxResponse
import com.frederikberg.mailverdict.contract.PendingSendResponse
import com.frederikberg.mailverdict.kit.api.OutboxCreateResult
import com.frederikberg.mailverdict.kit.api.OutboxUpload
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.RawBody
import kotlinx.coroutines.CompletableDeferred

/** Canned accounts, identities and messages; every submit is recorded and answered by [answer]. */
class FakeComposerBackend : ComposerBackend {
    val accounts = mutableListOf(account("a1", "me@one.test"), account("a2", "me@two.test"))
    val identities =
        mutableListOf(
            IdentityResponse("i1", "a1", "me@one.test", "Me", true, T),
            IdentityResponse("i2", "a1", "alias@one.test", "Me", false, T),
        )
    val messages = HashMap<String, MessageDetail>()
    val quotes = HashMap<String, String>()
    val files = HashMap<String, ByteArray>()
    val submitted = mutableListOf<Pair<OutboxCreateRequest, List<OutboxUpload>>>()

    /** When set, a submit waits for it: a request still in flight. */
    var gate: CompletableDeferred<Unit>? = null
    var answer: (OutboxCreateRequest) -> OutboxCreateResult = { sent(it) }

    override suspend fun accounts() = accounts

    override suspend fun identities() = identities

    override suspend fun message(id: String) = messages[id] ?: throw ApiError.Http(404, "no message $id")

    override suspend fun quote(id: String) = quotes[id] ?: "<p>${messages[id]?.bodyText}</p>"

    override suspend fun attachment(
        messageId: String,
        attachmentId: String,
    ) = files[attachmentId]?.let { RawBody(it, "application/octet-stream", null) } ?: throw ApiError.Http(404, "gone")

    override suspend fun submit(
        request: OutboxCreateRequest,
        uploads: List<OutboxUpload>,
    ): OutboxCreateResult {
        submitted += request to uploads
        gate?.await()
        return answer(request)
    }

    override suspend fun searchContacts(query: String) =
        listOf(
            ContactSearchHitOut(
                "c1",
                "Ada",
                "ada@example.com",
                com.frederikberg.mailverdict.contract.ContactSource.Contact,
            ),
        )

    companion object {
        const val T = "2026-11-14T09:00:00Z"

        fun account(
            id: String,
            user: String,
        ) = AccountResponse(
            id = id,
            name = id,
            imapHost = "imap.test",
            imapPort = 993,
            imapUser = user,
            createdAt = T,
            updatedAt = T,
        )

        fun sent(request: OutboxCreateRequest) =
            OutboxCreateResult.Sent(
                OutboxResponse("o1", request.accountId, request.kind, "pending", createdAt = T, updatedAt = T),
            )

        fun pending(
            id: String = "p1",
            sendAfter: String,
        ) = PendingSendResponse(id, "a1", sendAfter, T, to = listOf("ada@example.com"), subject = "Plan")

        fun message(
            id: String,
            from: String = "Ada <ada@example.com>",
            to: List<String> = listOf("alias@one.test"),
            subject: String = "Plan",
            body: String = "Which one?",
            draft: Boolean = false,
            attachments: List<AttachmentSummary> = emptyList(),
        ) = MessageDetail(
            id = id,
            accountId = "a1",
            folderId = "f1",
            threadId = "t1",
            messageId = "<$id@example.com>",
            subject = subject,
            fromAddr = from,
            toAddrs = AddressList(to),
            receivedAt = T,
            createdAt = T,
            bodyText = body,
            isDraft = draft,
            attachments = attachments,
        )
    }
}

/** Recovery kept in memory, counting writes. */
class MemoryRecovery : ComposeRecovery {
    val stored = HashMap<String, RecoveryContent>()
    var writes = 0

    override fun read(key: String) = stored[key]

    override fun write(
        key: String,
        content: RecoveryContent,
    ) {
        writes++
        stored[key] = content
    }

    override fun clear(key: String) {
        stored.remove(key)
    }
}
