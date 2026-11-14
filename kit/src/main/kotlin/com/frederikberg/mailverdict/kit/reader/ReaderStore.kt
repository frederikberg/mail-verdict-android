package com.frederikberg.mailverdict.kit.reader

import com.frederikberg.mailverdict.contract.MessageActionKind
import com.frederikberg.mailverdict.contract.MessageActionRequest
import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.contract.ThreadResponse
import com.frederikberg.mailverdict.kit.api.getMessage
import com.frederikberg.mailverdict.kit.api.getThread
import com.frederikberg.mailverdict.kit.api.performAction
import com.frederikberg.mailverdict.kit.cache.NoOfflineCache
import com.frederikberg.mailverdict.kit.cache.OfflineCache
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Every call the reader makes. */
interface ReaderBackend {
    suspend fun thread(messageId: String): ThreadResponse

    /** One message with its remote images included, for the per-message "show images". */
    suspend fun messageWithImages(messageId: String): MessageDetail

    suspend fun markRead(messageId: String)
}

class ApiReaderBackend(
    private val api: ApiClient,
) : ReaderBackend {
    override suspend fun thread(messageId: String) = api.getThread(messageId)

    override suspend fun messageWithImages(messageId: String) = api.getMessage(messageId, loadImages = true)

    override suspend fun markRead(messageId: String) {
        api.performAction(messageId, MessageActionRequest(MessageActionKind.MarkRead))
    }
}

/** What reading a page marks read. */
object ReaderReadPolicy {
    /**
     * The opened message is marked read when its page settles, except a draft (about to be edited, not
     * read) and the one message someone explicitly marked unread while looking at it: otherwise the
     * reader would undo that action the moment it lands.
     */
    fun shouldMarkRead(
        message: MessageDetail,
        explicitlyUnreadId: String?,
    ): Boolean = !message.isDraft && !message.isSeen && message.id != explicitlyUnreadId

    /**
     * A row grouped by conversation counts every unread message of its thread, so reading it marks the
     * rest of the thread read too, within the row's own folders and leaving out the opened message,
     * which [shouldMarkRead] already covers.
     */
    fun conversationIdsToMarkRead(
        thread: List<MessageDetail>,
        openedId: String,
        folderIds: Set<String>,
    ): List<String> =
        thread
            .filter {
                it.folderId in folderIds && !it.isSeen && it.id != openedId && !it.isDraft
            }.map { it.id }
}

/**
 * One conversation in the reader: newest message first, the opened one expanded and every other
 * collapsed to a fixed header, so nothing above the message being read changes height while it loads.
 * A read the server cannot answer falls back to the conversation saved when it was last opened.
 */
class ReaderStore(
    private val openedId: String,
    /** Folders the opened row lives in: reading a grouped row marks the rest of its conversation read within them. */
    private val folderIds: Set<String>,
    private val backend: ReaderBackend,
    private val cache: OfflineCache = NoOfflineCache,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(ReaderState(openedId = openedId, expandedIds = setOf(openedId)))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var details: Map<String, MessageDetail> = emptyMap()
    private var imagesLoaded: Set<String> = emptySet()
    private var explicitlyUnreadId: String? = null

    suspend fun load() {
        _state.update { it.copy(phase = ReaderPhase.Loading, notice = null) }
        try {
            val thread = backend.thread(openedId)
            install(thread, savedAtMs = null)
            runCatching { cache.saveConversation(openedId, thread) }
            markRead(thread)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val saved = if (e.isServerUnreachable()) cache.conversation(openedId) else null
            if (saved != null) {
                install(saved.thread, saved.savedAtMs)
            } else {
                _state.update { it.copy(phase = ReaderPhase.Failed(e.userMessage())) }
            }
        }
    }

    private fun install(
        thread: ThreadResponse,
        savedAtMs: Long?,
    ) {
        details = thread.messages.associateBy { it.id }
        val primary = thread.messages.firstOrNull { it.id == openedId } ?: thread.messages.lastOrNull()
        _state.update {
            it.copy(
                phase = ReaderPhase.Loaded,
                subject = primary?.subject?.takeIf(String::isNotBlank) ?: "(no subject)",
                messages = thread.messages.asReversed().map { m -> ReaderMessages.make(m, m.id in imagesLoaded) },
                savedCopyAtMs = savedAtMs,
            )
        }
    }

    private suspend fun markRead(thread: ThreadResponse) {
        val opened = thread.messages.firstOrNull { it.id == openedId }
        val ids = mutableListOf<String>()
        if (opened != null && ReaderReadPolicy.shouldMarkRead(opened, explicitlyUnreadId)) ids += opened.id
        ids += ReaderReadPolicy.conversationIdsToMarkRead(thread.messages, openedId, folderIds)
        for (id in ids) {
            try {
                backend.markRead(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The row stays unread on the server; the next open tries again.
            }
        }
    }

    fun toggleExpanded(messageId: String) {
        _state.update {
            it.copy(
                expandedIds =
                    if (messageId in
                        it.expandedIds
                    ) {
                        it.expandedIds - messageId
                    } else {
                        it.expandedIds + messageId
                    },
            )
        }
    }

    fun showFullBody(messageId: String) {
        _state.update { it.copy(fullBodyIds = it.fullBodyIds + messageId) }
    }

    /** Re-reads one message with its remote images included, for this message only. */
    suspend fun loadImages(messageId: String) {
        try {
            val detail = backend.messageWithImages(messageId)
            imagesLoaded = imagesLoaded + messageId
            details = details + (messageId to detail)
            _state.update { state ->
                state.copy(
                    messages =
                        state.messages.map {
                            if (it.id ==
                                messageId
                            ) {
                                ReaderMessages.make(detail, imagesLoaded = true)
                            } else {
                                it
                            }
                        },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(notice = "Could not load images: ${e.userMessage()}") }
        }
    }

    fun reportNotice(text: String) {
        _state.update { it.copy(notice = text) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun Throwable.userMessage(): String =
        (this as? ApiError)?.userMessage ?: message.orEmpty().ifBlank { "Something went wrong." }

    private fun Throwable.isServerUnreachable(): Boolean =
        this is ApiError.Transport || (this is ApiError.Http && statusCode in setOf(502, 503, 504))
}
