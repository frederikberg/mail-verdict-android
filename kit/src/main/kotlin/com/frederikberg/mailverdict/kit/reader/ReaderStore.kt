package com.frederikberg.mailverdict.kit.reader

import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.contract.ThreadResponse
import com.frederikberg.mailverdict.kit.actions.ActionContext
import com.frederikberg.mailverdict.kit.actions.ActionQueue
import com.frederikberg.mailverdict.kit.actions.IntentProjection
import com.frederikberg.mailverdict.kit.actions.IntentRequest
import com.frederikberg.mailverdict.kit.actions.TriageAction
import com.frederikberg.mailverdict.kit.actions.TriageRequests
import com.frederikberg.mailverdict.kit.actions.UiAction
import com.frederikberg.mailverdict.kit.api.getMessage
import com.frederikberg.mailverdict.kit.api.getThread
import com.frederikberg.mailverdict.kit.api.listFolders
import com.frederikberg.mailverdict.kit.api.sendSpamFeedback
import com.frederikberg.mailverdict.kit.cache.NoOfflineCache
import com.frederikberg.mailverdict.kit.cache.OfflineCache
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Every call the reader makes that is not a queued mail action. */
interface ReaderBackend {
    suspend fun thread(messageId: String): ThreadResponse

    /** One message with its remote images included, for the per-message "show images". */
    suspend fun messageWithImages(messageId: String): MessageDetail

    /** Special-use role by folder id, so the reader knows Trash and Junk. */
    suspend fun folderRoles(accountId: String): Map<String, String?>

    /** Tells the spam filter it was right or wrong; the server files the message accordingly. */
    suspend fun sendFeedback(
        accountId: String,
        messageId: String,
        isSpam: Boolean,
    )
}

class ApiReaderBackend(
    private val api: ApiClient,
) : ReaderBackend {
    override suspend fun thread(messageId: String) = api.getThread(messageId)

    override suspend fun messageWithImages(messageId: String) = api.getMessage(messageId, loadImages = true)

    override suspend fun folderRoles(accountId: String) =
        api
            .listFolders(
                accountId,
            ).associate { it.id to it.specialUse }

    override suspend fun sendFeedback(
        accountId: String,
        messageId: String,
        isSpam: Boolean,
    ) {
        api.sendSpamFeedback(accountId, messageId, isSpam)
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
 *
 * Marking read and every action on a message go through the [actions] queue, and what the reader
 * shows is the server's messages with the queue's outstanding intents applied, so an action shows at
 * once and no read landing meanwhile can undo it.
 */
class ReaderStore(
    private val openedId: String,
    /** Folders the opened row lives in: reading a grouped row marks the rest of its conversation read within them. */
    private val folderIds: Set<String>,
    private val backend: ReaderBackend,
    private val cache: OfflineCache = NoOfflineCache,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val actions: ActionQueue? = null,
    scope: CoroutineScope? = null,
) {
    private val _state = MutableStateFlow(ReaderState(openedId = openedId, expandedIds = setOf(openedId)))
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val lock = Any()
    private var thread: List<MessageDetail> = emptyList()
    private var readAt = 0
    private var imagesLoaded: Set<String> = emptySet()
    private var explicitlyUnreadId: String? = null
    private var folderRoles: Map<String, String?> = emptyMap()

    init {
        val queue = actions
        if (queue != null && scope != null) scope.launch { queue.state.collect { rebuild() } }
    }

    suspend fun load() {
        _state.update { it.copy(phase = ReaderPhase.Loading, notice = null) }
        val sequence = actions?.state?.value?.sequence ?: 0
        try {
            val thread = backend.thread(openedId)
            install(thread, savedAtMs = null, readAt = sequence)
            runCatching { cache.saveConversation(openedId, thread) }
            loadFolderRoles(thread)
            markRead(thread)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val saved = if (e.isServerUnreachable()) cache.conversation(openedId) else null
            if (saved != null) {
                install(saved.thread, saved.savedAtMs, readAt = 0)
            } else {
                _state.update { it.copy(phase = ReaderPhase.Failed(e.userMessage())) }
            }
        }
    }

    private fun install(
        response: ThreadResponse,
        savedAtMs: Long?,
        readAt: Int,
    ) {
        synchronized(lock) {
            thread = response.messages
            this.readAt = readAt
        }
        val primary = response.messages.firstOrNull { it.id == openedId } ?: response.messages.lastOrNull()
        _state.update {
            it.copy(
                phase = ReaderPhase.Loaded,
                subject = primary?.subject?.takeIf(String::isNotBlank) ?: "(no subject)",
                savedCopyAtMs = savedAtMs,
            )
        }
        rebuild()
    }

    /** The messages as the person sees them: the server's, with the queue's outstanding intents applied. */
    private fun rebuild() {
        val (messages, intents, sequence) =
            synchronized(lock) {
                Triple(
                    thread,
                    actions
                        ?.state
                        ?.value
                        ?.intents
                        .orEmpty(),
                    readAt,
                )
            }
        if (messages.isEmpty()) return
        _state.update { state ->
            state.copy(
                messages =
                    messages.asReversed().map {
                        ReaderMessages.make(IntentProjection.message(it, intents, sequence), it.id in imagesLoaded)
                    },
            )
        }
    }

    private suspend fun loadFolderRoles(response: ThreadResponse) {
        val accountId = response.messages.firstOrNull()?.accountId ?: return
        runCatching { backend.folderRoles(accountId) }.getOrNull()?.let { synchronized(lock) { folderRoles = it } }
    }

    private fun markRead(response: ThreadResponse) {
        val queue = actions ?: return
        val opened = response.messages.firstOrNull { it.id == openedId } ?: return
        if (ReaderReadPolicy.shouldMarkRead(opened, explicitlyUnreadId)) {
            queue.enqueue(IntentRequest(opened.accountId, TriageAction.MarkRead, listOf(opened.id)))
        }
        val others = ReaderReadPolicy.conversationIdsToMarkRead(response.messages, openedId, folderIds)
        if (others.isNotEmpty()) {
            queue.enqueue(
                IntentRequest(
                    opened.accountId,
                    TriageAction.MarkRead,
                    others,
                    delivery =
                        com.frederikberg.mailverdict.kit.actions.Delivery
                            .Bulk(expandThreads = false),
                ),
            )
        }
    }

    // Actions

    /** What the menu offers for [messageId]. */
    fun actionContext(messageId: String): ActionContext? {
        val message = _state.value.messages.firstOrNull { it.id == messageId } ?: return null
        val roles = synchronized(lock) { folderRoles }
        return ActionContext(
            isRead = !message.isUnread,
            isStarred = message.isFlagged,
            isInTrash = roles[message.folderId] == "trash",
            isInJunk = roles[message.folderId] == "junk",
            isInGlacier = message.isGlacier,
            verdictIsSpam = message.isSpam,
        )
    }

    /** The folder [messageId] is in, where a move does nothing. */
    fun folderOf(messageId: String): String? =
        _state.value.messages
            .firstOrNull { it.id == messageId }
            ?.folderId

    fun accountOf(messageId: String): String? =
        synchronized(
            lock,
        ) { thread.firstOrNull { it.id == messageId }?.accountId }

    /** Hands [action] on [messageId] to the queue with an Undo offer where one applies. True when the message leaves its folder, so the reader has nothing left to show. */
    fun perform(
        action: UiAction,
        messageId: String,
        targetFolderId: String? = null,
    ): Boolean {
        val queue = actions ?: return false
        val detail = synchronized(lock) { thread.firstOrNull { it.id == messageId } } ?: return false
        val request = TriageRequests.forMessage(detail, action, targetFolderId) ?: return false
        // The one message someone marked unread while looking at it is not marked read again by the reader.
        if (action == UiAction.MarkUnread) explicitlyUnreadId = messageId
        if (action == UiAction.MarkRead && explicitlyUnreadId == messageId) explicitlyUnreadId = null
        val id = queue.enqueue(request)
        TriageRequests.undoNotice(request.action, 1)?.let { queue.offerUndo(it, listOf(id)) }
        return request.action.removesFromList
    }

    /** Confirms the spam filter's verdict on [messageId], or corrects it. */
    suspend fun sendVerdictFeedback(
        messageId: String,
        confirming: Boolean,
    ) {
        val message = _state.value.messages.firstOrNull { it.id == messageId } ?: return
        val verdict = message.isSpam ?: return
        val accountId = accountOf(messageId) ?: return
        try {
            backend.sendFeedback(accountId, messageId, if (confirming) verdict else !verdict)
            _state.update { it.copy(notice = if (confirming) "Verdict confirmed" else "Verdict corrected") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(notice = "Could not send feedback: ${e.userMessage()}") }
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
            synchronized(lock) {
                imagesLoaded = imagesLoaded + messageId
                thread = thread.map { if (it.id == messageId) detail else it }
            }
            rebuild()
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
