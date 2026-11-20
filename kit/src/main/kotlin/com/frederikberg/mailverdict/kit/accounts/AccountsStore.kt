package com.frederikberg.mailverdict.kit.accounts

import com.frederikberg.mailverdict.contract.AccountCreateRequest
import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.AccountUpdateRequest
import com.frederikberg.mailverdict.contract.SyncStatusResponse
import com.frederikberg.mailverdict.kit.api.createAccount
import com.frederikberg.mailverdict.kit.api.deleteAccount
import com.frederikberg.mailverdict.kit.api.getSyncStatus
import com.frederikberg.mailverdict.kit.api.listAccounts
import com.frederikberg.mailverdict.kit.api.setAccountEmoji
import com.frederikberg.mailverdict.kit.api.triggerSync
import com.frederikberg.mailverdict.kit.api.updateAccount
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import com.frederikberg.mailverdict.kit.net.writeOutcome
import com.frederikberg.mailverdict.kit.stores.mailboxes.AccountConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An account with what the server says about its sync, as the list and the detail show it. */
data class AccountRow(
    val account: AccountResponse,
    val sync: SyncStatusResponse? = null,
) {
    val id: String get() = account.id

    /** Whether an `error` state is terminal or the account is still working through its retries. */
    val connection: AccountConnectionState
        get() = AccountConnectionState.classify(account.state, hasFullSync = sync?.lastFullSync != null)

    /** The last time mail was fetched, if it ever was. */
    val lastSync: String? get() = sync?.lastIncrSync ?: sync?.lastFullSync
}

data class AccountsState(
    val rows: List<AccountRow> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
) {
    fun row(accountId: String): AccountRow? = rows.firstOrNull { it.id == accountId }
}

/**
 * The person's accounts and what can be done to them. Every write answers a [WriteOutcome] the
 * screen shows in place, and a successful one is reflected in [state] from the server's answer, never
 * from what was sent.
 */
class AccountsStore(
    private val api: ApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AccountsState())
    val state: StateFlow<AccountsState> = _state.asStateFlow()

    /** Reads the accounts and each one's sync status. Keeps what is on screen when the read fails, and says so. */
    suspend fun load() {
        _state.update { it.copy(isLoading = !it.hasLoaded, errorMessage = null) }
        try {
            val rows =
                coroutineScope {
                    api
                        .listAccounts()
                        .map { account ->
                            async { AccountRow(account, runCatchingApi { api.getSyncStatus(account.id) }) }
                        }.awaitAll()
                }
            _state.update { it.copy(rows = rows, isLoading = false, hasLoaded = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { it.copy(isLoading = false, errorMessage = e.userMessage) }
        }
    }

    suspend fun create(request: AccountCreateRequest): WriteOutcome {
        val outcome = writeOutcome { api.createAccount(request) }
        if (outcome.isDone) load()
        return outcome
    }

    suspend fun update(
        accountId: String,
        request: AccountUpdateRequest,
    ): WriteOutcome =
        writeOutcome {
            val updated = api.updateAccount(accountId, request)
            replace(updated)
        }

    suspend fun delete(accountId: String): WriteOutcome {
        val outcome = writeOutcome { api.deleteAccount(accountId) }
        if (outcome.isDone) _state.update { s -> s.copy(rows = s.rows.filterNot { it.id == accountId }) }
        return outcome
    }

    suspend fun syncNow(accountId: String): WriteOutcome {
        val outcome = writeOutcome { api.triggerSync(accountId) }
        if (outcome.isDone) refreshSync(accountId)
        return outcome
    }

    /** Sets the emoji that marks the account, or removes it with null. */
    suspend fun setEmoji(
        accountId: String,
        emoji: String?,
    ): WriteOutcome {
        val outcome = writeOutcome { api.setAccountEmoji(accountId, emoji) }
        if (outcome.isDone) {
            _state.update { s ->
                s.copy(
                    rows =
                        s.rows.map {
                            if (it.id ==
                                accountId
                            ) {
                                it.copy(account = it.account.copy(emoji = emoji))
                            } else {
                                it
                            }
                        },
                )
            }
        }
        return outcome
    }

    /** `account.changed` and a resync re-read the list. */
    fun follow(events: Flow<List<LiveEvent>>) {
        scope.launch {
            events.collect { batch ->
                val stale =
                    batch.any {
                        it.invalidation is LiveInvalidation.AccountsChanged ||
                            it.invalidation is LiveInvalidation.Resync
                    }
                if (stale && _state.value.hasLoaded) load()
            }
        }
    }

    private fun replace(account: AccountResponse) {
        _state.update { s ->
            s.copy(rows = s.rows.map { if (it.id == account.id) it.copy(account = account) else it })
        }
    }

    private suspend fun refreshSync(accountId: String) {
        val sync = runCatchingApi { api.getSyncStatus(accountId) } ?: return
        _state.update { s -> s.copy(rows = s.rows.map { if (it.id == accountId) it.copy(sync = sync) else it }) }
    }

    /** A read that is only a courtesy: the answer, or null when the server refused or was unreachable. */
    private suspend fun <T> runCatchingApi(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            null
        }
}
