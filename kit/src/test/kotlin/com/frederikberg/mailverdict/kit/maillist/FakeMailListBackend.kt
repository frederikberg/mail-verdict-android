package com.frederikberg.mailverdict.kit.maillist

import com.frederikberg.mailverdict.contract.FolderResponse
import com.frederikberg.mailverdict.contract.MessageListResponse
import com.frederikberg.mailverdict.contract.MessageLocation
import com.frederikberg.mailverdict.contract.MessageSummary
import com.frederikberg.mailverdict.contract.UnifiedFolderResponse
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

/** A server holding [messages] newest first, answering pages the way the real list routes do. */
class FakeMailListBackend(
    var messages: List<MessageSummary>,
) : MailListBackend {
    data class Call(
        val cursor: ListCursor,
        val threaded: Boolean,
        val unreadOnly: Boolean,
        val limit: Int,
    )

    val calls = CopyOnWriteArrayList<Call>()
    val syncs = CopyOnWriteArrayList<String>()

    /** Set to make every page call fail with it. */
    @Volatile var failure: Exception? = null

    /** When set, the next page call waits for it, so a test can look at the store while a read is in flight. */
    @Volatile var gate: CompletableDeferred<Unit>? = null

    private fun visible(unreadOnly: Boolean) =
        messages
            .filter {
                !unreadOnly || !it.isSeen
            }.sortedWith { a, b -> if (MailListWindow.sitsAbove(a, b)) -1 else 1 }

    override suspend fun page(
        scope: ListScope,
        threaded: Boolean,
        unreadOnly: Boolean,
        cursor: ListCursor,
        limit: Int,
    ): MessageListResponse {
        calls += Call(cursor, threaded, unreadOnly, limit)
        gate?.also { gate = null }?.await()
        failure?.let { throw it }
        val all = visible(unreadOnly)
        return when (cursor) {
            ListCursor.Newest -> MessageListResponse(all.take(limit), hasMore = all.size > limit)
            is ListCursor.OlderThan -> {
                val index = all.indexOfFirst { it.id == cursor.messageId }
                val rest = all.drop(index + 1)
                MessageListResponse(rest.take(limit), hasMore = rest.size > limit)
            }
            is ListCursor.NewerThan -> {
                val index = all.indexOfFirst { it.id == cursor.messageId }
                val above = all.take(index)
                val page = above.takeLast(limit)
                MessageListResponse(page, hasMore = false, hasMoreNewer = above.size > limit)
            }
            is ListCursor.Around -> {
                val index = all.indexOfFirst { it.id == cursor.messageId }
                if (index < 0) throw ApiError.Detail("Not Found", 404)
                val from = maxOf(0, index - limit / 2)
                val page = all.drop(from).take(limit)
                MessageListResponse(page, hasMore = from + page.size < all.size, hasMoreNewer = from > 0)
            }
        }
    }

    override suspend fun locate(messageId: String): MessageLocation {
        val m = messages.first { it.id == messageId }
        return MessageLocation(m.id, m.accountId, m.folderId, m.threadId)
    }

    override suspend fun folders(accountId: String): List<FolderResponse> =
        listOf(
            FolderResponse(
                id = "f1",
                accountId = accountId,
                imapName = "INBOX",
                specialUse = "inbox",
                totalCount = messages.size,
                unreadCount =
                    messages
                        .count {
                            !it.isSeen
                        },
            ),
            FolderResponse(id = "f2", accountId = accountId, imapName = "Sent", specialUse = "sent"),
        )

    override suspend fun unifiedViews(): List<UnifiedFolderResponse> = emptyList()

    override suspend fun sync(accountId: String) {
        syncs += accountId
    }
}
