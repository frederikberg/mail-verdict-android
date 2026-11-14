package com.frederikberg.mailverdict.kit.stores.mailboxes

import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.kit.api.getFolderOrder
import com.frederikberg.mailverdict.kit.api.getSyncStatus
import com.frederikberg.mailverdict.kit.api.listAccounts
import com.frederikberg.mailverdict.kit.api.listOutbox
import com.frederikberg.mailverdict.kit.api.listUnifiedFolders
import com.frederikberg.mailverdict.kit.format.folderDisplayName
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the overview needs: the unified views and one collapsible section per account with its
 * folders and counts, plus the banner for mail that could not be sent. It reads the whole picture on
 * [load] and then, as live events arrive, only what each event can have changed.
 */
class MailboxesStore(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val collapse: CollapseStore = InMemoryCollapseStore(),
    private val syncPollIntervalMs: Long = SYNC_POLL_INTERVAL_MS,
) {
    private val _state = MutableStateFlow(MailboxesState(collapsedKeys = collapse.collapsedKeys()))
    val state: StateFlow<MailboxesState> = _state.asStateFlow()

    /**
     * Whether each account has a saved folder order, as the last full read saw it: a folder list is
     * ordered differently with one, and the folder-order route does not say.
     */
    private val hasCustomFolderOrder = ConcurrentHashMap<String, Boolean>()

    private val lock = Any()
    private var reloadJob: Job? = null
    private var queuedPlan: MailboxesReloadPlan? = null
    private var pollJob: Job? = null

    fun toggleCollapsed(key: String) {
        val collapsed = key !in _state.value.collapsedKeys
        collapse.setCollapsed(key, collapsed)
        _state.update { it.copy(collapsedKeys = if (collapsed) it.collapsedKeys + key else it.collapsedKeys - key) }
    }

    /** Reads everything. Joins a reload already running instead of starting a second. */
    suspend fun load() = reload(MailboxesReloadPlan(everything = true))

    /** Re-reads what [events] make stale, and everything again when the device's network path returns after a failed load. */
    fun follow(
        events: Flow<List<LiveEvent>>,
        pathRestored: Flow<Unit>,
    ) {
        scope.launch {
            events.collect { batch ->
                val plan =
                    MailboxesSupport.reloadPlan(batch.map { it.invalidation }) { folderId ->
                        _state.value.accountSections
                            .firstOrNull { section -> section.folders.any { it.id == folderId } }
                            ?.id
                    }
                if (!plan.isEmpty) launch { reload(plan) }
            }
        }
        scope.launch {
            pathRestored.collect { if (_state.value.loadError != null) launch { load() } }
        }
    }

    /** Polls account health while the screen is visible; the screen stops it when it leaves. */
    fun startSyncStatusPolling() {
        pollJob?.cancel()
        pollJob =
            scope.launch {
                while (true) {
                    delay(syncPollIntervalMs)
                    refreshConnectionStates()
                }
            }
    }

    fun stopSyncStatusPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Runs [plan] after any reload in progress, merged with whatever else queued meanwhile: one
     * running and at most one waiting, however many events arrive during a sync.
     */
    private suspend fun reload(plan: MailboxesReloadPlan) {
        val job =
            synchronized(lock) {
                val running = reloadJob
                if (running != null) {
                    queuedPlan = queuedPlan?.merged(plan) ?: plan
                    return@synchronized running
                }
                queuedPlan = plan
                scope
                    .launch {
                        while (true) {
                            val next =
                                synchronized(lock) {
                                    queuedPlan.also { queuedPlan = null } ?: run {
                                        reloadJob = null
                                        return@launch
                                    }
                                }
                            perform(next)
                        }
                    }.also { reloadJob = it }
            }
        job.join()
    }

    private suspend fun perform(plan: MailboxesReloadPlan) {
        if (plan.everything) return loadEverything()
        val applied = if (plan.counts) reloadCounts(plan) else true
        if (plan.deadOutbox) reloadDeadOutbox()
        // Counts for a screen not fully loaded yet, or an account it does not show, need the rest.
        if (!applied) loadEverything()
    }

    private suspend fun loadEverything() {
        _state.update {
            it.copy(
                isLoading = it.unifiedRows.isEmpty() && it.accountSections.isEmpty(),
                loadError = null,
            )
        }
        try {
            val (accounts, unified, dead) =
                coroutineScope {
                    val accounts = async { api.listAccounts() }
                    val unified = async { api.listUnifiedFolders() }
                    // The banner is a courtesy: the overview still loads without it.
                    val dead =
                        async {
                            try {
                                api.listOutbox(status = "dead")
                            } catch (_: ApiError) {
                                null
                            }
                        }
                    Triple(accounts.await(), unified.await(), dead.await())
                }
            accounts.forEach { hasCustomFolderOrder[it.id] = !it.folderOrder.isNullOrEmpty() }
            val sections = coroutineScope { accounts.map { async { buildSection(it) } }.awaitAll() }
            val names = accountNames(accounts)
            _state.update {
                it.copy(
                    hasAnyAccount = accounts.isNotEmpty(),
                    unifiedRows =
                        unified.map { view ->
                            MailboxesUnifiedRow(
                                view.id,
                                view.unifiedName,
                                view.emoji,
                                view.unreadCount,
                                MailboxesSupport.contributingAccounts(view, accounts).map { a -> a.emoji ?: "✉️" },
                            )
                        },
                    accountSections = sections,
                    deadOutboxBanner = dead?.let { rows -> deadBanner(rows.map { r -> r.accountId }, names) },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { it.copy(loadError = e.userMessage) }
        }
        _state.update { it.copy(isLoading = false) }
    }

    private fun accountNames(accounts: List<AccountResponse>): (String) -> String? =
        { id -> accounts.firstOrNull { it.id == id }?.name }

    private fun deadBanner(
        accountIds: List<String>,
        nameOf: (String) -> String?,
    ): String? =
        MailboxesSupport.deadOutboxBannerText(
            accountIds.size,
            MailboxesSupport.distinctAccountNames(accountIds, nameOf),
        )

    /** One account's section: its health, and its folders unless it never connected. A failure here never blocks another account's section. */
    private suspend fun buildSection(account: AccountResponse): MailboxesAccountSection {
        val sync = optional { api.getSyncStatus(account.id) }
        val connection = AccountConnectionState.classify(account.state, hasFullSync = sync?.lastFullSync != null)
        val folders =
            if (connection == AccountConnectionState.NeverConnected) {
                emptyList()
            } else {
                folderRows(account.id, !account.folderOrder.isNullOrEmpty()).orEmpty()
            }
        return MailboxesAccountSection(account.id, account.name, account.emoji, connection, account.stateError, folders)
    }

    /** An account's folder rows in display order; null when the folder order could not be read. */
    private suspend fun folderRows(
        accountId: String,
        hasCustomOrder: Boolean,
    ): List<MailboxesFolderRow>? {
        val order = optional { api.getFolderOrder(accountId) } ?: return null
        return MailboxesSupport.orderFolders(order.folders, hasCustomOrder).map { item ->
            MailboxesFolderRow(
                id = item.folderId,
                accountId = accountId,
                displayName = folderDisplayName(item.imapName, item.displayName, item.specialUse),
                specialUse = item.specialUse,
                badgeCount = MailboxesSupport.folderBadgeCount(item.specialUse, item.unreadCount, item.totalCount),
                totalCount = item.totalCount,
                kind = item.kind,
            )
        }
    }

    /**
     * Re-reads the unified rows and the folder rows of the accounts the plan names: the counts a mail
     * event can have changed. False when that cannot be done from what is already on screen and a full
     * load is needed instead.
     */
    private suspend fun reloadCounts(plan: MailboxesReloadPlan): Boolean {
        val shown = _state.value.accountSections.associateBy { it.id }
        val wanted = if (plan.countsForAllAccounts) shown.keys else plan.countAccountIds
        if (shown.isEmpty() || !shown.keys.containsAll(wanted) || wanted.any { hasCustomFolderOrder[it] == null }) {
            return false
        }
        val (unified, rows) =
            coroutineScope {
                val unified = async { optional { api.listUnifiedFolders() } }
                val rows =
                    wanted
                        .filter { shown.getValue(it).connectionState != AccountConnectionState.NeverConnected }
                        .map { id -> async { id to folderRows(id, hasCustomFolderOrder.getValue(id)) } }
                        .awaitAll()
                        .mapNotNull { (id, folders) -> folders?.let { id to it } }
                        .toMap()
                unified.await() to rows
            }
        _state.update { current ->
            current.copy(
                accountSections =
                    current.accountSections.map { s ->
                        rows[s.id]?.let { s.copy(folders = it) }
                            ?: s
                    },
            )
        }
        if (unified == null) return true
        // A view added or removed changes more than a count.
        if (unified.map { it.id }.toSet() !=
            _state.value.unifiedRows
                .map { it.id }
                .toSet()
        ) {
            return false
        }
        _state.update { current ->
            val previous = current.unifiedRows.associateBy { it.id }
            current.copy(
                unifiedRows =
                    unified.map { view ->
                        MailboxesUnifiedRow(
                            view.id,
                            view.unifiedName,
                            view.emoji,
                            view.unreadCount,
                            previous[view.id]?.contributingAccountEmojis.orEmpty(),
                        )
                    },
            )
        }
        return true
    }

    private suspend fun reloadDeadOutbox() {
        val dead = optional { api.listOutbox(status = "dead") } ?: return
        val sections = _state.value.accountSections
        val text = deadBanner(dead.map { it.accountId }) { id -> sections.firstOrNull { it.id == id }?.name }
        _state.update { it.copy(deadOutboxBanner = text) }
    }

    /** A failed poll is dropped rather than trusted: it must never downgrade a healthy section on a transient network hiccup. */
    private suspend fun refreshConnectionStates() {
        for (section in _state.value.accountSections) {
            val sync = optional { api.getSyncStatus(section.id) } ?: continue
            val connection = AccountConnectionState.classify(sync.state, hasFullSync = sync.lastFullSync != null)
            _state.update { current ->
                current.copy(
                    accountSections =
                        current.accountSections.map {
                            if (it.id == section.id) {
                                it.copy(connectionState = connection, stateError = sync.stateError ?: it.stateError)
                            } else {
                                it
                            }
                        },
                )
            }
        }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            null
        }

    companion object {
        const val SYNC_POLL_INTERVAL_MS: Long = 30_000
    }
}
