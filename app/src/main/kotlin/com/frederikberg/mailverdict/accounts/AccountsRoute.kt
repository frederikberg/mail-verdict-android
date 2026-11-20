package com.frederikberg.mailverdict.accounts

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.frederikberg.mailverdict.contract.AccountUpdateRequest
import com.frederikberg.mailverdict.kit.accounts.AccountFormInput
import com.frederikberg.mailverdict.kit.accounts.AccountsStore
import com.frederikberg.mailverdict.kit.accounts.IdentitiesStore
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import kotlinx.coroutines.launch

/** The account list wired to its store. */
@Composable
fun AccountsRoute(
    store: AccountsStore,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (accountId: String) -> Unit,
) {
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { store.load() }
    AccountsScreen(
        state = state,
        callbacks =
            AccountsCallbacks(
                onBack = onBack,
                onAdd = onAdd,
                onOpen = { onOpen(it.id) },
                onRetry = { scope.launch { store.load() } },
            ),
    )
}

/** The add form: a created account leaves the form, a refusal stays under the button with everything typed kept. */
@Composable
fun AddAccountRoute(
    store: AccountsStore,
    onBack: () -> Unit,
) {
    var submitting by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AccountFormScreen(
        editing = false,
        initial = AccountFormInput(),
        submitting = submitting,
        serverError = serverError,
        onBack = onBack,
        onCreate = { request ->
            scope.launch {
                submitting = true
                serverError = null
                when (val outcome = store.create(request)) {
                    WriteOutcome.Done -> onBack()
                    is WriteOutcome.Failed -> serverError = outcome.message
                }
                submitting = false
            }
        },
        onUpdate = {},
    )
}

/** The edit form of one account, once the list holding it has been read. */
@Composable
fun EditAccountRoute(
    store: AccountsStore,
    accountId: String,
    onBack: () -> Unit,
) {
    val state by store.state.collectAsState()
    var submitting by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { if (!store.state.value.hasLoaded) store.load() }
    val row = state.row(accountId) ?: return
    AccountFormScreen(
        editing = true,
        initial = AccountFormInput.of(row.account),
        submitting = submitting,
        serverError = serverError,
        onBack = onBack,
        onCreate = {},
        onUpdate = { request ->
            scope.launch {
                submitting = true
                serverError = null
                when (val outcome = store.update(accountId, request)) {
                    WriteOutcome.Done -> onBack()
                    is WriteOutcome.Failed -> serverError = outcome.message
                }
                submitting = false
            }
        },
    )
}

/** One account with its identities; a refused change is said in a snackbar and the screen keeps showing the server's state. */
@Composable
fun AccountDetailRoute(
    store: AccountsStore,
    identities: IdentitiesStore,
    accountId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val state by store.state.collectAsState()
    val identityState by identities.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, identities) {
        store.load()
        identities.load()
    }

    fun run(block: suspend () -> WriteOutcome) {
        scope.launch {
            val outcome = block()
            if (outcome is WriteOutcome.Failed) snackbar.showSnackbar(outcome.message)
        }
    }

    AccountDetailScreen(
        row = state.row(accountId),
        identities = identityState,
        snackbar = snackbar,
        callbacks =
            AccountDetailCallbacks(
                onBack = onBack,
                onEdit = onEdit,
                onSyncNow = { run { store.syncNow(accountId) } },
                onSetActive = { run { store.update(accountId, AccountUpdateRequest(isActive = it)) } },
                onSetSpam = { run { store.update(accountId, AccountUpdateRequest(spamEnabled = it)) } },
                onSetOrders = { run { store.update(accountId, AccountUpdateRequest(ordersEnabled = it)) } },
                onSetEmoji = { emoji -> run { store.setEmoji(accountId, emoji) } },
                onDelete = {
                    scope.launch {
                        when (val outcome = store.delete(accountId)) {
                            WriteOutcome.Done -> onBack()
                            is WriteOutcome.Failed -> snackbar.showSnackbar(outcome.message)
                        }
                    }
                },
                onAddIdentity = { name, address, onAdded ->
                    scope.launch {
                        when (val outcome = identities.add(address, name)) {
                            WriteOutcome.Done -> onAdded()
                            is WriteOutcome.Failed -> snackbar.showSnackbar(outcome.message)
                        }
                    }
                },
                onMakeDefault = { run { identities.makeDefault(it.id) } },
                onRemoveIdentity = { run { identities.remove(it.id) } },
            ),
    )
}
