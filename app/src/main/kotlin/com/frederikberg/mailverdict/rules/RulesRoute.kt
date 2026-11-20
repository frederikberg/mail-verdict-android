package com.frederikberg.mailverdict.rules

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.contract.AccountUpdateRequest
import com.frederikberg.mailverdict.kit.accounts.AccountsStore
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import com.frederikberg.mailverdict.kit.rules.RulesStore
import com.frederikberg.mailverdict.kit.rules.RulesView
import com.frederikberg.mailverdict.kit.session.Connection
import kotlinx.coroutines.launch

/** The rule list of [connection], kept for as long as it lasts. */
fun AppGraph.rulesStore(connection: Connection): RulesStore =
    connectionStores.get(connection, RulesStore::class) { RulesStore(connection.api) }

/** The rules screen wired to its stores; a refused change is said in a snackbar and the list shows what the server holds. */
@Composable
fun RulesRoute(
    store: RulesStore,
    accounts: AccountsStore,
    onBack: () -> Unit,
) {
    val state by store.state.collectAsState()
    val accountsState by accounts.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        accounts.load()
        store.load(
            accounts.state.value.rows
                .map { it.id },
        )
    }
    LaunchedEffect(store) { load() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            store.dismissNotice()
        }
    }

    fun run(block: suspend () -> WriteOutcome) {
        scope.launch {
            val outcome = block()
            if (outcome is WriteOutcome.Failed && !outcome.isConflict) snackbar.showSnackbar(outcome.message)
        }
    }

    val view =
        remember(state, accountsState, filter) {
            RulesView.build(state, accountsState.rows.map { it.account }, filter)
        }
    RulesScreen(
        state = state,
        view = view,
        filter = filter,
        onFilter = { filter = it },
        accounts = accountsState.rows,
        snackbar = snackbar,
        callbacks =
            RulesCallbacks(
                onBack = onBack,
                onRetry = { scope.launch { load() } },
                onToggle = { row, enabled -> run { store.setEnabled(row.id, enabled) } },
                onMove = { row, direction -> run { store.move(row.id, direction, row.groupMembers) } },
                onDelete = { row -> run { store.delete(row.id) } },
                onPause = { run { store.setPaused(true) } },
                onResume = { run { store.setPaused(false) } },
                onOpenHistory = { run { store.loadRevisions() } },
                onRestore = { revision -> run { store.restore(revision) } },
                onSetSpam = { id, on -> run { accounts.update(id, AccountUpdateRequest(spamEnabled = on)) } },
                onSetOrders = { id, on -> run { accounts.update(id, AccountUpdateRequest(ordersEnabled = on)) } },
            ),
    )
}
