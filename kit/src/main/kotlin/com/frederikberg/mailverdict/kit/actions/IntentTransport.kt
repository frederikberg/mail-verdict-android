package com.frederikberg.mailverdict.kit.actions

import com.frederikberg.mailverdict.contract.BulkActionKind
import com.frederikberg.mailverdict.contract.BulkActionRequest
import com.frederikberg.mailverdict.contract.BulkActionResponse
import com.frederikberg.mailverdict.contract.BulkActionSource
import com.frederikberg.mailverdict.contract.FolderResponse
import com.frederikberg.mailverdict.contract.MessageActionKind
import com.frederikberg.mailverdict.contract.MessageActionRequest
import com.frederikberg.mailverdict.contract.MessageActionResponse
import com.frederikberg.mailverdict.contract.ThreadResponse
import com.frederikberg.mailverdict.kit.api.getMessage
import com.frederikberg.mailverdict.kit.api.getThread
import com.frederikberg.mailverdict.kit.api.listFolders
import com.frederikberg.mailverdict.kit.api.locateMessage
import com.frederikberg.mailverdict.kit.api.performAction
import com.frederikberg.mailverdict.kit.api.performBulkAction
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException

/** A message as the queue checks it before sending again an intent that may already have landed. */
data class MessageState(
    val folderId: String,
    /** Null when the lookup did not read them. */
    val isSeen: Boolean? = null,
    val isFlagged: Boolean? = null,
)

/** The requests an intent is delivered with; [ApiIntentTransport] is the real one. */
interface IntentTransport {
    suspend fun deliverMessageAction(
        messageId: String,
        action: TriageAction,
        targetFolderId: String?,
        expectedFolderId: String?,
        idempotencyKey: String,
        confirm: Boolean,
    ): MessageActionResponse

    suspend fun deliverBulkAction(
        accountId: String,
        request: BulkActionRequest,
    ): BulkActionResponse

    /** Null when the message no longer exists. [includeFlags] reads read and star state too, which costs the whole message rather than its location. */
    suspend fun fetchMessageState(
        messageId: String,
        includeFlags: Boolean,
    ): MessageState?

    suspend fun fetchFolders(accountId: String): List<FolderResponse>

    suspend fun fetchThread(messageId: String): ThreadResponse
}

class ApiIntentTransport(
    private val api: ApiClient,
) : IntentTransport {
    override suspend fun deliverMessageAction(
        messageId: String,
        action: TriageAction,
        targetFolderId: String?,
        expectedFolderId: String?,
        idempotencyKey: String,
        confirm: Boolean,
    ) = api.performAction(
        messageId,
        MessageActionRequest(
            action = MessageActionKind(action.raw),
            targetFolderId = targetFolderId,
            expectedFolderId = expectedFolderId,
            idempotencyKey = idempotencyKey,
            confirm = confirm,
        ),
    )

    override suspend fun deliverBulkAction(
        accountId: String,
        request: BulkActionRequest,
    ) = api.performBulkAction(accountId, request)

    override suspend fun fetchMessageState(
        messageId: String,
        includeFlags: Boolean,
    ): MessageState? =
        try {
            if (includeFlags) {
                api.getMessage(messageId).let { MessageState(it.folderId, it.isSeen, it.isFlagged) }
            } else {
                MessageState(api.locateMessage(messageId).folderId)
            }
        } catch (e: ApiError) {
            if (e.statusCode == 404) null else throw e
        }

    override suspend fun fetchFolders(accountId: String) = api.listFolders(accountId)

    override suspend fun fetchThread(messageId: String) = api.getThread(messageId)
}

/** What a delivery attempt means for the intent. */
sealed interface IntentDelivery {
    /** [filed]: where the server says it put each message. */
    data class Delivered(
        val affectedCount: Int? = null,
        val sources: List<BulkActionSource> = emptyList(),
        val filed: Map<String, String> = emptyMap(),
    ) : IntentDelivery

    /** A guarded action whose messages had all moved on: nothing written, nothing to retry. */
    data object NotApplied : IntentDelivery

    /** 404: the message is gone. */
    data object Gone : IntentDelivery

    /** Worth trying again: no response, a timeout, a rate limit or a server error. [mayHaveLanded] when the request could have been applied without its answer coming back. */
    data class Retry(
        val reason: String,
        val mayHaveLanded: Boolean,
    ) : IntentDelivery

    /** The credential or the login proxy refused it: nothing was applied, and nothing is sent for a while. */
    data class Hold(
        val reason: String,
    ) : IntentDelivery

    /** 503: the server is still applying an earlier attempt. Nothing new was applied; asked again shortly, without counting as an attempt. */
    data class Busy(
        val reason: String,
    ) : IntentDelivery

    /** The server refused the request itself; sending it again would be refused again. */
    data class Refused(
        val reason: String,
    ) : IntentDelivery

    companion object {
        fun classify(error: Throwable): IntentDelivery {
            if (error is CancellationException) throw error
            if (error !is ApiError) return Retry(error.message.orEmpty().ifBlank { "Something went wrong." }, true)
            return when (error) {
                is ApiError.Detail, is ApiError.Http -> {
                    val status = error.statusCode ?: 0
                    when {
                        status == 404 -> Gone
                        error.endsSession -> Hold(error.userMessage)
                        status == 408 || status == 425 || status == 429 -> Retry(error.userMessage, false)
                        status == 503 -> Busy(error.userMessage)
                        status >= 500 -> Retry(error.userMessage, true)
                        else -> Refused(error.userMessage)
                    }
                }
                is ApiError.Transport -> Retry(error.userMessage, true)
                // The status check passed before the body was read: the server accepted the request.
                is ApiError.Decoding -> Delivered()
                is ApiError.ProxyRequiresBrowserLogin -> Hold(error.userMessage)
            }
        }

        fun of(
            response: BulkActionResponse,
            intent: MailIntent,
        ): IntentDelivery {
            if (!response.success) {
                val errors = response.errors.joinToString("; ")
                return Refused(errors.ifEmpty { "The server did not apply it" })
            }
            val skipped = response.skippedIds.toSet()
            if (skipped.isNotEmpty() &&
                response.affectedCount == 0 &&
                skipped.containsAll(intent.messageIds)
            ) {
                return NotApplied
            }
            val filed = mutableMapOf<String, String>()
            response.targetFolderId?.let { target ->
                val moved = if (response.sources.isEmpty()) intent.messageIds else response.sources.map { it.id }
                moved.filter { it !in skipped }.forEach { filed[it] = target }
            }
            return Delivered(response.affectedCount, response.sources, filed)
        }
    }
}

internal fun TriageAction.toBulk(): BulkActionKind = BulkActionKind(raw)
