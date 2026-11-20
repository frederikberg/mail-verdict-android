package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.ContactSearchHitOut
import com.frederikberg.mailverdict.contract.IdentityResponse
import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.contract.OutboxCreateRequest
import com.frederikberg.mailverdict.contract.PendingSendResponse
import com.frederikberg.mailverdict.kit.api.OutboxCreateResult
import com.frederikberg.mailverdict.kit.api.OutboxUpload
import com.frederikberg.mailverdict.kit.api.cancelPendingSend
import com.frederikberg.mailverdict.kit.api.createOutbox
import com.frederikberg.mailverdict.kit.api.getAttachment
import com.frederikberg.mailverdict.kit.api.getMessage
import com.frederikberg.mailverdict.kit.api.getPendingSendAttachment
import com.frederikberg.mailverdict.kit.api.getQuote
import com.frederikberg.mailverdict.kit.api.listAccounts
import com.frederikberg.mailverdict.kit.api.listIdentities
import com.frederikberg.mailverdict.kit.api.listPendingSends
import com.frederikberg.mailverdict.kit.api.searchContacts
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RawBody

/** Everything the composer reaches outside itself; the app's is the API, a test's canned answers. */
interface ComposerBackend {
    suspend fun accounts(): List<AccountResponse>

    suspend fun identities(): List<IdentityResponse>

    suspend fun message(id: String): MessageDetail

    /** The message's body as safe-to-send HTML: a quote, or a draft reopened. */
    suspend fun quote(id: String): String

    suspend fun attachment(
        messageId: String,
        attachmentId: String,
    ): RawBody

    suspend fun submit(
        request: OutboxCreateRequest,
        uploads: List<OutboxUpload>,
    ): OutboxCreateResult

    suspend fun searchContacts(query: String): List<ContactSearchHitOut>
}

/** The undo window's side of the outbox. */
interface UndoSendBackend {
    suspend fun pending(): List<PendingSendResponse>

    suspend fun cancel(pendingSendId: String)

    suspend fun pendingAttachment(
        pendingSendId: String,
        attachmentId: String,
    ): RawBody
}

class ApiComposerBackend(
    private val api: ApiClient,
) : ComposerBackend,
    UndoSendBackend {
    override suspend fun accounts() = api.listAccounts()

    override suspend fun identities() = api.listIdentities()

    override suspend fun message(id: String) = api.getMessage(id)

    override suspend fun quote(id: String) = api.getQuote(id).html

    override suspend fun attachment(
        messageId: String,
        attachmentId: String,
    ) = api.getAttachment(messageId, attachmentId)

    override suspend fun submit(
        request: OutboxCreateRequest,
        uploads: List<OutboxUpload>,
    ) = api.createOutbox(request, uploads)

    override suspend fun searchContacts(query: String) = api.searchContacts(query)

    override suspend fun pending() = api.listPendingSends()

    override suspend fun cancel(pendingSendId: String) = api.cancelPendingSend(pendingSendId)

    override suspend fun pendingAttachment(
        pendingSendId: String,
        attachmentId: String,
    ) = api.getPendingSendAttachment(pendingSendId, attachmentId)
}
