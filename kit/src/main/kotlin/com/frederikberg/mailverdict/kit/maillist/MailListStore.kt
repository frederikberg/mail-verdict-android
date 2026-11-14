package com.frederikberg.mailverdict.kit.maillist

import com.frederikberg.mailverdict.contract.MessageSummary
import com.frederikberg.mailverdict.kit.actions.ActionContext
import com.frederikberg.mailverdict.kit.actions.ActionQueue
import com.frederikberg.mailverdict.kit.actions.IntentObserver
import com.frederikberg.mailverdict.kit.actions.IntentProjection
import com.frederikberg.mailverdict.kit.actions.MailIntent
import com.frederikberg.mailverdict.kit.actions.ProjectionScope
import com.frederikberg.mailverdict.kit.actions.TriageRequests
import com.frederikberg.mailverdict.kit.actions.UiAction
import com.frederikberg.mailverdict.kit.cache.NoOfflineCache
import com.frederikberg.mailverdict.kit.cache.OfflineCache
import com.frederikberg.mailverdict.kit.cache.OfflineListKey
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the reader was in a list: the row at the top edge and how far into it, so a return restores by identity and never by pixel offset. */
data class ViewportAnchor(
    val rowId: String,
    val offsetPx: Int,
)

/**
 * One message list, a folder or a unified view. It owns the fetch window (a page around wherever the
 * list opened, older and newer pages on demand, one bounded request that refreshes all of it), the
 * unread filter, the grouping toggle and selection.
 *
 * Scroll position is not here: the screen owns the viewport. [state] tells it when the rows became a
 * different list ([ListIdentity]) rather than the same list changed, and [savedViewport] keeps the
 * reader's place across a visit to the reader.
 *
 * Reads that fail while the server cannot be reached fall back to the newest page saved in the
 * [cache]; the first read the server answers replaces it.
 */
class MailListStore(
    val scope: ListScope,
    private val backend: MailListBackend,
    private val coroutineScope: CoroutineScope,
    private val preferences: MailListPreferences = InMemoryMailListPreferences(),
    private val cache: OfflineCache = NoOfflineCache,
    private val rowFactory: MailRowFactory = MailRowFactory(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val aroundMessageId: String? = null,
    /** Every action goes through it; the rows shown are the server's rows with its outstanding intents applied. */
    private val actions: ActionQueue? = null,
) {
    private val lock = Any()
    private var base: List<MessageSummary> = emptyList()

    /** The list the rows on screen are; installed when a first page lands, so rows and identity change together. */
    private var identity = ListIdentity(scope, preferences.threaded(), unreadOnly = false)

    /** The toggles as the person set them; [identity] follows once the page they asked for has landed. */
    private var threaded = identity.threaded
    private var unreadOnly = identity.unreadOnly
    private var phase: ListPhase = ListPhase.Loading
    private var hasOlder = false
    private var hasNewer = false
    private var loadingOlder = false
    private var loadingNewer = false
    private var pendingArrivals = 0
    private var savedCopyAtMs: Long? = null
    private var landing: ListLanding? = null
    private var selecting = false
    private var selected: Set<String> = emptySet()
    private var context = ListContext()
    private var notice: String? = null
    private var rows: List<MailRowData> = emptyList()

    /** Rows read while the unread filter is on keep showing until the filter or the list changes. */
    private var keptWhileUnread: Set<String> = emptySet()

    /** The queue's sequence when the oldest part of [base] was read ([IntentProjection.applies]). */
    private var baseSequence = 0

    /** [base] with the queue's intents applied: what the person sees, and what an action is built from. */
    private var projectedBase: List<MessageSummary> = emptyList()

    private var generation = 0
    private var started = false
    private var wasConnected: Boolean? = null
    private var refreshJob: Job? = null
    private var refreshAgain = false
    private var lastRefreshFailure: Throwable? = null
    private val pageLock = Mutex()

    @Volatile
    var savedViewport: ViewportAnchor? = null
        private set

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<MailListState> = _state.asStateFlow()

    private val intentObserver =
        object : IntentObserver {
            override fun intentSnapshots(messageIds: Set<String>): List<MessageSummary> =
                synchronized(lock) { base.filter { it.id in messageIds } }

            /** Data read before these settled keeps their effect once they are gone from the queue. */
            override fun intentsWillRetire(intents: List<MailIntent>) {
                synchronized(lock) {
                    base = IntentProjection.rows(base, intents, baseSequence, projectionScope())
                    rebuildRows()
                }
                publish()
            }

            /** Whatever the server derives from a change (counts, conversation rows) is read again once it has the change. */
            override fun intentsDidSettle(intents: List<MailIntent>) {
                val accounts = synchronized(lock) { accountIdsInScope() }
                if (intents.any { it.accountId in accounts }) requestRefresh()
            }
        }

    init {
        actions?.let { queue ->
            queue.addObserver(intentObserver)
            coroutineScope.launch {
                queue.state.collect {
                    synchronized(lock) { rebuildRows() }
                    publish()
                }
            }
        }
    }

    // Loading

    /** Loads the first page (around the message the list was opened for, or at the newest edge) and the context. Idempotent. */
    suspend fun start() {
        if (synchronized(lock) { started.also { started = true } }) return
        coroutineScope {
            val contextLoad = async { loadContext() }
            loadFirstPage(aroundMessageId, synchronized(lock) { generation })
            contextLoad.await()
        }
    }

    suspend fun retry() = replaceList()

    /** A different list in the same scope: rows stay on screen until the new first page lands, then both change together. */
    private suspend fun replaceList() {
        val expected =
            synchronized(lock) {
                generation += 1
                keptWhileUnread = emptySet()
                selected = emptySet()
                selecting = false
                pendingArrivals = 0
                generation
            }
        publish()
        loadFirstPage(null, expected)
    }

    fun consumeLanding(): ListLanding? =
        synchronized(lock) { landing.also { landing = null } }.also { if (it != null) publish() }

    private suspend fun loadFirstPage(
        aroundId: String?,
        expected: Int,
    ) {
        val (threaded, unreadOnly) = synchronized(lock) { this.threaded to this.unreadOnly }
        synchronized(lock) { if (rows.isEmpty()) phase = ListPhase.Loading }
        publish()
        val readAt = queueSequence()
        try {
            var landedAround = false
            val page =
                if (aroundId != null) {
                    try {
                        backend
                            .page(scope, threaded, unreadOnly, ListCursor.Around(aroundId), MailListWindow.PAGE_SIZE)
                            .also { landedAround = true }
                    } catch (e: ApiError) {
                        // Not a member of this list (any more): the newest edge is where the reader would otherwise land.
                        if (e.statusCode != 404) throw e
                        backend.page(scope, threaded, unreadOnly, ListCursor.Newest, MailListWindow.PAGE_SIZE)
                    }
                } else {
                    backend.page(scope, threaded, unreadOnly, ListCursor.Newest, MailListWindow.PAGE_SIZE)
                }
            val installed =
                synchronized(lock) {
                    if (generation != expected) return@synchronized false
                    base = page.messages
                    baseSequence = readAt
                    hasOlder = page.hasMore
                    hasNewer = page.hasMoreNewer
                    install(ListIdentity(scope, threaded, unreadOnly, expected))
                    phase = ListPhase.Loaded
                    savedCopyAtMs = null
                    rebuildRows()
                    true
                }
            if (!installed) return
            publish()
            saveOfflineCopy()
            if (landedAround && aroundId != null) {
                val rowId = representativeRowId(aroundId, threaded)
                synchronized(lock) {
                    if (generation == expected &&
                        rowId != null
                    ) {
                        landing = ListLanding.RevealInUpperThird(rowId)
                    }
                }
                publish()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val showsThisList =
                synchronized(lock) {
                    rows.isNotEmpty() &&
                        identity.threaded == threaded &&
                        identity.unreadOnly == unreadOnly
                }
            if (!showsThisList &&
                e.isServerUnreachable() &&
                showOfflineCopy(threaded, unreadOnly, aroundId, expected)
            ) {
                return
            }
            synchronized(lock) {
                if (generation != expected) return
                if (rows.isEmpty()) {
                    phase = ListPhase.Failed(e.userMessage())
                } else {
                    notice =
                        "Could not load messages: ${e.userMessage()}"
                }
            }
            publish()
        }
    }

    private suspend fun showOfflineCopy(
        threaded: Boolean,
        unreadOnly: Boolean,
        aroundId: String?,
        expected: Int,
    ): Boolean {
        val saved = cache.list(OfflineListKey(scope, threaded, unreadOnly)) ?: return false
        synchronized(lock) {
            if (generation != expected) return false
            base = saved.rows
            // Read at an unknown time: every intent not yet retired applies over it.
            baseSequence = 0
            hasOlder = saved.hasOlder
            hasNewer = false
            install(ListIdentity(scope, threaded, unreadOnly, expected))
            phase = ListPhase.Loaded
            savedCopyAtMs = saved.savedAtMs
            rebuildRows()
            if (aroundId != null && base.any { it.id == aroundId }) landing = ListLanding.RevealInUpperThird(aroundId)
        }
        publish()
        return true
    }

    /** Keeps the rows just read for reading offline: only a window that starts at the newest message, which is what a list opened offline shows. */
    private fun saveOfflineCopy() {
        val (rowsToSave, older, key) =
            synchronized(lock) {
                if (hasNewer || base.isEmpty() || savedCopyAtMs != null) return
                Triple(base, hasOlder, OfflineListKey(scope, identity.threaded, identity.unreadOnly))
            }
        coroutineScope.launch { runCatching { cache.saveList(key, rowsToSave, older) } }
    }

    /** Grouped by conversation the server centres an `around` page on the row standing for the target's conversation, a different id, so the row is found by thread. */
    private suspend fun representativeRowId(
        target: String,
        threaded: Boolean,
    ): String? {
        synchronized(lock) { base.firstOrNull { it.id == target }?.let { return it.id } }
        if (!threaded) return null
        val location = runCatching { backend.locate(target) }.getOrNull() ?: return null
        return synchronized(lock) { base.firstOrNull { it.threadId == location.threadId }?.id }
    }

    // Paging

    suspend fun loadOlder() = page(older = true)

    suspend fun loadNewer() = page(older = false)

    /** One page read at a time: a request arriving while one is in flight is dropped, and asked again once the rows grew and the reader is still near the edge. */
    private suspend fun page(older: Boolean) {
        if (!pageLock.tryLock()) return
        try {
            val window =
                synchronized(lock) {
                    val ok = if (older) hasOlder else hasNewer
                    if (!ok || phase != ListPhase.Loaded || base.isEmpty()) return
                    if (older) loadingOlder = true else loadingNewer = true
                    identity
                }
            publish()
            try {
                val cursor =
                    synchronized(lock) {
                        if (older) ListCursor.OlderThan(base.last().id) else ListCursor.NewerThan(base.first().id)
                    }
                val readAt = queueSequence()
                val response = backend.page(scope, window.threaded, window.unreadOnly, cursor, MailListWindow.PAGE_SIZE)
                var reachedNewestEdge = false
                synchronized(lock) {
                    if (identity != window) return@synchronized
                    baseSequence = minOf(baseSequence, readAt)
                    if (older) {
                        base = MailListWindow.appendingOlder(response.messages, base)
                        hasOlder = response.hasMore
                    } else {
                        base = MailListWindow.prependingNewer(response.messages, base)
                        hasNewer = response.hasMoreNewer
                        reachedNewestEdge = !hasNewer
                    }
                    rebuildRows()
                }
                if (reachedNewestEdge) catchUpAfterReachingNewestEdge(window)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left as it was: the next scroll toward the edge asks again.
            } finally {
                synchronized(lock) {
                    loadingOlder = false
                    loadingNewer = false
                }
                publish()
            }
        } finally {
            pageLock.unlock()
        }
    }

    /** Something may have arrived between the page that reached the newest edge being requested and landing; one more read closes the gap before the window counts as caught up. */
    private suspend fun catchUpAfterReachingNewestEdge(started: ListIdentity) {
        val first =
            synchronized(lock) {
                if (pendingArrivals == 0) return
                pendingArrivals = 0
                base.firstOrNull()?.id
            } ?: return
        val readAt = queueSequence()
        val response =
            runCatching {
                backend.page(
                    scope,
                    started.threaded,
                    started.unreadOnly,
                    ListCursor.NewerThan(first),
                    MailListWindow.PAGE_SIZE,
                )
            }.getOrNull() ?: return
        synchronized(lock) {
            if (identity != started) return
            baseSequence = minOf(baseSequence, readAt)
            base = MailListWindow.prependingNewer(response.messages, base)
            rebuildRows()
        }
    }

    // Refreshing

    /**
     * Re-reads the whole loaded window in one request. Never more than one in flight: a request
     * arriving meanwhile queues exactly one more read. Awaitable, for pull to refresh.
     */
    suspend fun refresh() {
        val job =
            synchronized(lock) {
                refreshJob?.let {
                    refreshAgain = true
                    return@synchronized it
                }
                coroutineScope
                    .launch {
                        while (true) {
                            synchronized(lock) { refreshAgain = false }
                            pageLock.withLock { }
                            refreshOnce()
                            val again =
                                synchronized(lock) {
                                    if (!refreshAgain) refreshJob = null
                                    refreshAgain
                                }
                            if (!again) break
                        }
                    }.also { refreshJob = it }
            }
        job.join()
    }

    fun requestRefresh() {
        coroutineScope.launch { refresh() }
    }

    private suspend fun refreshOnce() {
        synchronized(lock) { lastRefreshFailure = null }
        val readAt = queueSequence()
        val snapshot =
            synchronized(lock) {
                if (phase != ListPhase.Loaded || base.isEmpty()) return
                // Taken from the queue as it is now, not as this store's collector last saw it.
                rebuildRows()
                val preserve = if (identity.unreadOnly) keptWhileUnread else emptySet()
                // A row kept while the unread filter is on stays as the reader last saw it, read, even once the change that made it so has left the queue.
                val shown = projectedBase.associateBy { it.id }
                val current = base.map { if (it.id in preserve) shown[it.id] ?: it else it }
                RefreshInput(identity, current, hasNewer, hasOlder, savedCopyAtMs != null, preserve)
            }
        val limit = MailListWindow.refreshLimit(snapshot.current.size)
        try {
            if (snapshot.hasNewer) {
                val last = snapshot.current.last()
                val response =
                    backend.page(
                        scope,
                        snapshot.identity.threaded,
                        snapshot.identity.unreadOnly,
                        ListCursor.NewerThan(last.id),
                        limit,
                    )
                val merged =
                    MailListWindow.mergeRefreshedFromBelow(
                        snapshot.current,
                        response.messages,
                        response.hasMoreNewer,
                        snapshot.preserve,
                    )
                synchronized(lock) {
                    if (identity != snapshot.identity) return
                    base = merged.rows
                    baseSequence = readAt
                    hasNewer = merged.hasNewer
                    savedCopyAtMs = null
                    rebuildRows()
                }
            } else {
                val response =
                    backend.page(
                        scope,
                        snapshot.identity.threaded,
                        snapshot.identity.unreadOnly,
                        ListCursor.Newest,
                        limit,
                    )
                // A window drawn from a saved copy is replaced by the first read the server answers; only a window the server itself gave is spliced.
                val merged =
                    if (snapshot.fromSavedCopy) {
                        MailListWindow.replacingSavedCopy(
                            snapshot.current,
                            response.messages,
                            response.hasMore,
                            snapshot.preserve,
                        )
                    } else {
                        MailListWindow.mergeRefreshed(
                            snapshot.current,
                            response.messages,
                            response.hasMore,
                            snapshot.hasOlder,
                            snapshot.preserve,
                        )
                    }
                synchronized(lock) {
                    if (identity != snapshot.identity) return
                    base = merged.rows
                    baseSequence = readAt
                    hasOlder = merged.hasMore
                    savedCopyAtMs = null
                    rebuildRows()
                }
            }
            publish()
            saveOfflineCopy()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next change, foreground return or reconnect refreshes again.
            synchronized(lock) { lastRefreshFailure = e }
        }
    }

    private class RefreshInput(
        val identity: ListIdentity,
        val current: List<MessageSummary>,
        val hasNewer: Boolean,
        val hasOlder: Boolean,
        val fromSavedCopy: Boolean,
        val preserve: Set<String>,
    )

    /** Asks each account in scope to sync, then re-reads the list and its counts. A refresh that could not reach the server says so. */
    suspend fun pullToRefresh() {
        val accounts = synchronized(lock) { accountIdsInScope() }
        accounts.forEach { runCatching { backend.sync(it) } }
        refresh()
        loadContext()
        val failure = synchronized(lock) { lastRefreshFailure }
        if (failure != null && failure.isServerUnreachable()) {
            synchronized(lock) { notice = "Could not refresh: ${failure.userMessage()}" }
            publish()
        }
    }

    fun dismissNotice() {
        synchronized(lock) { if (notice == null) return else notice = null }
        publish()
    }

    // Context

    private suspend fun loadContext() {
        val accountIds: Set<String>
        var totalCount: Int? = null
        var unreadCount: Int? = null
        var members = emptySet<String>()
        when (scope) {
            is ListScope.Folder -> accountIds = setOf(scope.accountId)
            is ListScope.Unified -> {
                val view = runCatching { backend.unifiedViews() }.getOrNull()?.firstOrNull { it.id == scope.viewId }
                totalCount = view?.totalCount
                unreadCount = view?.unreadCount
                members = view?.folders?.mapTo(HashSet()) { it.folderId }.orEmpty()
                accountIds = view?.folders?.mapTo(HashSet()) { it.accountId }.orEmpty()
            }
        }
        val roles = HashMap<String, String?>()
        var folderCounts: Pair<Int, Int>? = null
        for (accountId in accountIds) {
            val folders = runCatching { backend.folders(accountId) }.getOrNull() ?: continue
            folders.forEach { roles[it.id] = it.specialUse }
            if (scope is ListScope.Folder) {
                folders.firstOrNull { it.id == scope.folderId }?.let { folderCounts = it.totalCount to it.unreadCount }
            }
        }
        synchronized(lock) {
            val previous = context
            context =
                ListContext(
                    folderRoles = if (roles.isEmpty()) previous.folderRoles else roles,
                    totalCount = folderCounts?.first ?: totalCount ?: previous.totalCount,
                    unreadCount = folderCounts?.second ?: unreadCount ?: previous.unreadCount,
                    memberFolderIds = members.ifEmpty { previous.memberFolderIds },
                    accountIds = accountIds,
                )
            rebuildRows()
        }
        publish()
    }

    private var countsJob: Job? = null
    private var countsAgain = false

    /** Never more than one counts read running; requests arriving meanwhile queue exactly one more. */
    private fun requestCountsRefresh() {
        synchronized(lock) {
            if (countsJob != null) {
                countsAgain = true
                return
            }
            countsJob =
                coroutineScope.launch {
                    while (true) {
                        synchronized(lock) { countsAgain = false }
                        loadContext()
                        if (synchronized(lock) { (!countsAgain).also { if (it) countsJob = null } }) break
                    }
                }
        }
    }

    private fun accountIdsInScope(): Set<String> =
        when (scope) {
            is ListScope.Folder -> setOf(scope.accountId)
            is ListScope.Unified -> context.accountIds
        }

    // Live updates

    /** Re-reads what [events] make stale; a reconnect or the network path returning re-reads the window instead of trusting it. */
    fun follow(
        events: Flow<List<LiveEvent>>,
        pathRestored: Flow<Unit>,
        connected: Flow<Boolean>? = null,
    ) {
        coroutineScope.launch { events.collect { batch -> applyEvents(batch) } }
        coroutineScope.launch { pathRestored.collect { networkPathRestored() } }
        connected?.let { coroutineScope.launch { it.collect(::setConnected) } }
    }

    fun setConnected(connected: Boolean) {
        val wasDisconnected = synchronized(lock) { (wasConnected == false).also { wasConnected = connected } }
        if (connected && wasDisconnected) readAgainAfterReconnect()
    }

    /** The device's network path came back: a list showing a saved page or an error asks the server now. */
    fun networkPathRestored() {
        val needsRead = synchronized(lock) { savedCopyAtMs != null || phase is ListPhase.Failed }
        if (needsRead) readAgainAfterReconnect()
    }

    private fun readAgainAfterReconnect() {
        if (synchronized(lock) { phase is ListPhase.Failed }) {
            coroutineScope.launch { retry() }
        } else {
            requestRefresh()
        }
    }

    fun apply(invalidations: List<LiveInvalidation>) {
        val now = nowMs()
        applyEvents(invalidations.map { LiveEvent(it, now) })
    }

    fun applyEvents(events: List<LiveEvent>) {
        var needsRefresh = false
        var needsContext = false
        var needsCounts = false
        var arrivals = 0
        synchronized(lock) {
            for (live in events) {
                val event = live.invalidation
                when (event) {
                    is LiveInvalidation.Resync -> {
                        needsRefresh = true
                        needsContext = true
                    }
                    is LiveInvalidation.MailNew -> {
                        if (!concerns(event.folderId)) continue
                        needsRefresh = true
                        needsCounts = true
                        if (hasNewer && base.none { it.id == event.messageId }) arrivals += 1
                    }
                    is LiveInvalidation.MailUpdated -> {
                        val concerned =
                            concerns(event.folderId) ||
                                event.oldFolderId?.let(::concerns) == true ||
                                (event.messageId?.let { id -> base.any { it.id == id } } ?: false)
                        if (concerned &&
                            !isSettledEcho(event.messageId, event.accountId, live.arrivedAtMs)
                        ) {
                            needsRefresh = true
                        }
                        if (LiveInvalidation.messageUpdateMovesCounts(event.changed) &&
                            (event.changed.contains("folder_id") || concerns(event.folderId))
                        ) {
                            needsCounts = true
                        }
                    }
                    is LiveInvalidation.MailDeleted -> {
                        if (concerns(event.folderId)) needsCounts = true
                        val id = event.messageId
                        if (id != null &&
                            base.any { it.id == id } &&
                            !isSettledEcho(id, event.accountId, live.arrivedAtMs)
                        ) {
                            base = base.filterNot { it.id == id }
                            rebuildRows()
                            needsRefresh = true
                        }
                    }
                    is LiveInvalidation.FolderSynced ->
                        if (concerns(event.folderId)) {
                            needsRefresh = true
                            needsCounts = true
                        }
                    is LiveInvalidation.VerdictIssued ->
                        if (base.any { it.id == event.messageId }) needsRefresh = true
                    is LiveInvalidation.FoldersChanged, is LiveInvalidation.AccountsChanged -> needsContext = true
                    else -> Unit
                }
            }
            pendingArrivals += arrivals
        }
        publish()
        if (needsRefresh) requestRefresh()
        if (needsContext) {
            coroutineScope.launch { loadContext() }
        } else if (needsCounts) {
            requestCountsRefresh()
        }
    }

    /** A folder id the event names belongs to this list; an event naming none concerns every list. */
    private fun concerns(folderId: String?): Boolean {
        if (folderId == null) return true
        return when (scope) {
            is ListScope.Folder -> scope.folderId == folderId
            is ListScope.Unified -> context.memberFolderIds.isEmpty() || folderId in context.memberFolderIds
        }
    }

    // Toggles and jumps

    suspend fun setThreaded(threaded: Boolean) {
        synchronized(lock) {
            if (this.threaded == threaded) return
            this.threaded = threaded
        }
        preferences.setThreaded(threaded)
        replaceList()
    }

    suspend fun setUnreadOnly(unreadOnly: Boolean) {
        synchronized(lock) {
            if (this.unreadOnly == unreadOnly) return
            this.unreadOnly = unreadOnly
        }
        replaceList()
    }

    /** The "new mail" capsule in a window that does not start at the newest message: replaced with a fresh newest page rather than paged all the way up. */
    suspend fun jumpToLatest() = replaceList()

    /** A row was opened in the reader: kept while the unread filter is on, so it does not vanish under the person who is reading it. */
    fun didOpen(rowId: String) {
        synchronized(lock) { if (unreadOnly) keptWhileUnread = keptWhileUnread + rowId }
    }

    /** Remembered across a visit to the reader; null forgets it (a new list starts at the top). */
    fun saveViewport(anchor: ViewportAnchor?) {
        savedViewport = anchor
    }

    // Selection

    fun startSelecting(rowId: String) {
        synchronized(lock) {
            selecting = true
            selected = setOf(rowId)
        }
        publish()
    }

    fun toggleSelection(rowId: String) {
        synchronized(lock) {
            if (!selecting) return
            selected = if (rowId in selected) selected - rowId else selected + rowId
        }
        publish()
    }

    /** Every loaded row; the rows not loaded yet are not part of it. */
    fun selectLoaded() {
        synchronized(lock) {
            selecting = true
            selected = rows.mapTo(LinkedHashSet()) { it.id }
        }
        publish()
    }

    fun stopSelecting() {
        synchronized(lock) {
            selecting = false
            selected = emptySet()
        }
        publish()
    }

    // Derivation

    /** A different list starts at the top, so the place remembered in the one it replaces is dropped. Must be called with [lock] held. */
    private fun install(next: ListIdentity) {
        if (next != identity) savedViewport = null
        identity = next
    }

    /** Must be called with [lock] held. */
    private fun rebuildRows() {
        val showRecipients = { m: MessageSummary ->
            context.folderRoles[m.folderId]?.let { it in RECIPIENT_ROLES } ==
                true
        }
        projectedBase =
            actions?.let { IntentProjection.rows(base, it.state.value.intents, baseSequence, projectionScope()) }
                ?: base
        rows = projectedBase.map { rowFactory.make(it, showRecipients(it)) }
    }

    /** What an intent needs to know about this list to apply itself to its rows. Must be called with [lock] held. */
    private fun projectionScope() =
        ProjectionScope(
            folderIds =
                when (scope) {
                    is ListScope.Folder -> setOf(scope.folderId)
                    is ListScope.Unified -> context.memberFolderIds
                },
            threaded = identity.threaded,
            hasOlder = hasOlder,
            hasNewer = hasNewer,
        )

    private fun queueSequence(): Int = actions?.state?.value?.sequence ?: 0

    private fun isSettledEcho(
        messageId: String?,
        accountId: String?,
        arrivedAtMs: Long,
    ): Boolean {
        val queue = actions ?: return false
        if (messageId == null) return false
        val accounts = accountId?.let { setOf(it) } ?: accountIdsInScope()
        return accounts.any { queue.isSettledEcho(messageId, it, arrivedAtMs) }
    }

    // Actions

    /** The rows of [ids] as the person sees them, with the queue's intents applied. */
    fun rowsFor(ids: Set<String>): List<MessageSummary> = synchronized(lock) { projectedBase.filter { it.id in ids } }

    /** What the menus offer for [ids]: read and star state, and whether they sit in Trash or Junk. */
    fun actionContext(ids: Set<String>): ActionContext {
        val rows = rowsFor(ids)
        val roles = synchronized(lock) { context.folderRoles }
        return ActionContext(
            isRead = rows.isNotEmpty() && rows.all { it.isSeen },
            isStarred = rows.isNotEmpty() && rows.all { it.isFlagged },
            isInTrash = rows.isNotEmpty() && rows.all { roles[it.folderId] == "trash" },
            isInJunk = rows.isNotEmpty() && rows.all { roles[it.folderId] == "junk" },
            isInGlacier = rows.isNotEmpty() && rows.all { it.isGlacier },
        )
    }

    /** The folders [ids] are in: where a move does nothing. */
    fun foldersOf(ids: Set<String>): Set<String> = rowsFor(ids).mapTo(HashSet()) { it.folderId }

    /** The accounts [ids] belong to. */
    fun accountsOf(ids: Set<String>): Set<String> = rowsFor(ids).mapTo(HashSet()) { it.accountId }

    /** Hands [action] on [ids] to the queue, with an Undo offer where the action can be undone, and ends selection. [targetFolderId] names where a move goes. */
    fun perform(
        action: UiAction,
        ids: Set<String>,
        targetFolderId: String? = null,
    ) {
        val queue = actions ?: return
        val rows = rowsFor(ids)
        val requests = TriageRequests.forRows(rows, action, identity.threaded, targetFolderId)
        if (requests.isEmpty()) return
        val queued = requests.map { queue.enqueue(it) }
        // The row leaves or changes now, not when the queue's own state next reaches this store's collector.
        synchronized(lock) { rebuildRows() }
        publish()
        action.action?.let { kind -> TriageRequests.undoNotice(kind, rows.size)?.let { queue.offerUndo(it, queued) } }
        stopSelecting()
    }

    private fun snapshot(): MailListState =
        MailListState(
            identity = identity,
            threaded = threaded,
            unreadOnly = unreadOnly,
            rows = rows,
            phase = phase,
            hasOlder = hasOlder,
            hasNewer = hasNewer,
            isLoadingOlder = loadingOlder,
            isLoadingNewer = loadingNewer,
            pendingArrivals = pendingArrivals,
            savedCopyAtMs = savedCopyAtMs,
            landing = landing,
            isSelecting = selecting,
            selectedIds = selected,
            context = context,
            notice = notice,
        )

    private fun publish() {
        synchronized(lock) { _state.value = snapshot() }
    }

    private fun Throwable.userMessage(): String =
        (this as? ApiError)?.userMessage ?: message.orEmpty().ifBlank { "Something went wrong." }

    private fun Throwable.isServerUnreachable(): Boolean =
        this is ApiError.Transport || (this is ApiError.Http && statusCode in UNREACHABLE_STATUSES)

    private companion object {
        val RECIPIENT_ROLES = setOf("sent", "drafts")
        val UNREACHABLE_STATUSES = setOf(502, 503, 504)
    }
}
