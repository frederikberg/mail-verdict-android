package com.frederikberg.mailverdict.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.kit.accounts.AccountRow
import com.frederikberg.mailverdict.kit.accounts.AccountsState
import com.frederikberg.mailverdict.kit.format.MailFormat
import com.frederikberg.mailverdict.kit.stores.mailboxes.AccountConnectionState

object AccountsTags {
    const val LIST = "accounts-list"
    const val ADD = "accounts-add"
    const val EMPTY = "accounts-empty"
    const val ERROR = "accounts-error"

    fun row(id: String) = "account:$id"
}

class AccountsCallbacks(
    val onBack: () -> Unit,
    val onAdd: () -> Unit,
    val onOpen: (AccountRow) -> Unit,
    val onRetry: () -> Unit,
)

/** The person's accounts, each with how it is doing, and the way to add another. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    state: AccountsState,
    callbacks: AccountsCallbacks,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = callbacks.onAdd, modifier = Modifier.testTag(AccountsTags.ADD)) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.accounts_add))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.errorMessage?.let { message ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag(AccountsTags.ERROR),
                ) {
                    Text(
                        stringResource(R.string.accounts_error, message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = callbacks.onRetry) { Text(stringResource(R.string.accounts_retry)) }
                }
            }
            when {
                state.isLoading && state.rows.isEmpty() ->
                    CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                state.hasLoaded && state.rows.isEmpty() -> EmptyAccounts(callbacks.onAdd)
                else ->
                    LazyColumn(Modifier.fillMaxSize().testTag(AccountsTags.LIST)) {
                        items(state.rows, key = { it.id }) { row ->
                            AccountLine(
                                row,
                                Modifier.testTag(AccountsTags.row(row.id)).clickable { callbacks.onOpen(row) },
                            )
                            HorizontalDivider()
                        }
                    }
            }
        }
    }
}

@Composable
private fun EmptyAccounts(onAdd: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(32.dp).testTag(AccountsTags.EMPTY),
    ) {
        Text(stringResource(R.string.accounts_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.accounts_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAdd) { Text(stringResource(R.string.accounts_add)) }
    }
}

@Composable
private fun AccountLine(
    row: AccountRow,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(row.account.emoji ?: "✉️", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) {
            Text(
                row.account.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                row.account.imapUser,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                row.lastSync?.let { stringResource(R.string.accounts_last_synced, MailFormat.listDate(it)) }
                    ?: stringResource(R.string.accounts_never_synced),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StateChip(row)
    }
}

/** The account's state in a word: an error that already synced once is still being retried. */
@Composable
fun StateChip(row: AccountRow) {
    val label = stateLabel(row)
    val isError = row.account.state == "error" && row.connection == AccountConnectionState.NeverConnected
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isError) colors.errorContainer else colors.secondaryContainer,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isError) colors.onErrorContainer else colors.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun stateLabel(row: AccountRow): String =
    if (row.connection == AccountConnectionState.Retrying) {
        stringResource(R.string.accounts_state_retrying)
    } else {
        when (row.account.state) {
            "created" -> stringResource(R.string.accounts_state_created)
            "syncing" -> stringResource(R.string.accounts_state_syncing)
            "disabled" -> stringResource(R.string.accounts_state_disabled)
            "active" -> stringResource(R.string.accounts_state_active)
            "error" -> stringResource(R.string.accounts_state_error)
            else -> row.account.state
        }
    }
