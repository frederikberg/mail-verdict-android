package com.frederikberg.mailverdict.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.IdentityResponse
import com.frederikberg.mailverdict.kit.accounts.AccountRow
import com.frederikberg.mailverdict.kit.accounts.IdentitiesState
import com.frederikberg.mailverdict.kit.format.MailFormat
import com.frederikberg.mailverdict.kit.stores.mailboxes.AccountConnectionState
import com.frederikberg.mailverdict.ui.FormTextField
import com.frederikberg.mailverdict.ui.SectionTitle

object AccountDetailTags {
    const val EDIT = "account-detail-edit"
    const val SYNC_NOW = "account-detail-sync-now"
    const val ACTIVE = "account-detail-active"
    const val SPAM = "account-detail-spam"
    const val ORDERS = "account-detail-orders"
    const val DELETE = "account-detail-delete"
    const val CONFIRM_DELETE = "account-detail-delete-confirm"
    const val EMOJI = "account-detail-emoji"
    const val EMOJI_REMOVE = "account-detail-emoji-remove"
    const val IDENTITY_NAME = "account-detail-identity-name"
    const val IDENTITY_ADDRESS = "account-detail-identity-address"
    const val IDENTITY_ADD = "account-detail-identity-add"
    const val NOT_FOUND = "account-detail-not-found"

    fun emojiChoice(emoji: String) = "account-detail-emoji-choice:$emoji"

    fun identity(id: String) = "identity:$id"

    fun identityDefault(id: String) = "identity-default:$id"

    fun identityRemove(id: String) = "identity-remove:$id"
}

class AccountDetailCallbacks(
    val onBack: () -> Unit,
    val onEdit: () -> Unit,
    val onSyncNow: () -> Unit,
    val onSetActive: (Boolean) -> Unit,
    val onSetSpam: (Boolean) -> Unit,
    val onSetOrders: (Boolean) -> Unit,
    val onSetEmoji: (String?) -> Unit,
    val onDelete: () -> Unit,
    /** Adds an identity; [onAdded] runs when the server accepted it, to empty the fields. */
    val onAddIdentity: (name: String, address: String, onAdded: () -> Unit) -> Unit,
    val onMakeDefault: (IdentityResponse) -> Unit,
    val onRemoveIdentity: (IdentityResponse) -> Unit,
)

/** One account: how it is doing, what it is set to, and the addresses it sends as. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    row: AccountRow?,
    identities: IdentitiesState,
    snackbar: SnackbarHostState,
    callbacks: AccountDetailCallbacks,
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var editingEmoji by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        row?.account?.name ?: stringResource(R.string.accounts_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (row != null) {
                        IconButton(onClick = callbacks.onEdit, modifier = Modifier.testTag(AccountDetailTags.EDIT)) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.account_detail_edit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (row == null) {
            Text(
                stringResource(R.string.account_detail_not_found),
                modifier = Modifier.padding(padding).padding(16.dp).testTag(AccountDetailTags.NOT_FOUND),
            )
            return@Scaffold
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(row, onEmoji = { editingEmoji = true })
            StatusBlock(row, callbacks)
            HorizontalDivider()
            Facts(row.account)
            HorizontalDivider()
            SwitchLine(
                R.string.account_detail_spam,
                row.account.spamEnabled,
                AccountDetailTags.SPAM,
                callbacks.onSetSpam,
            )
            SwitchLine(
                R.string.account_detail_orders,
                row.account.ordersEnabled,
                AccountDetailTags.ORDERS,
                callbacks.onSetOrders,
            )
            HorizontalDivider()
            IdentitiesBlock(identities, callbacks)
            HorizontalDivider()
            OutlinedButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier.fillMaxWidth().testTag(AccountDetailTags.DELETE),
            ) { Text(stringResource(R.string.account_detail_delete), color = MaterialTheme.colorScheme.error) }
        }
        if (confirmingDelete) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text(stringResource(R.string.account_detail_delete_title, row.account.name)) },
                text = { Text(stringResource(R.string.account_detail_delete_text)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmingDelete = false
                            callbacks.onDelete()
                        },
                        modifier = Modifier.testTag(AccountDetailTags.CONFIRM_DELETE),
                    ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirmingDelete = false },
                    ) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
        if (editingEmoji) {
            EmojiDialog(
                current = row.account.emoji,
                onDismiss = { editingEmoji = false },
                onSet = {
                    editingEmoji = false
                    callbacks.onSetEmoji(it)
                },
            )
        }
    }
}

@Composable
private fun Header(
    row: AccountRow,
    onEmoji: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            row.account.emoji ?: "✉️",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.clickable(onClick = onEmoji).testTag(AccountDetailTags.EMOJI).padding(4.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(row.account.name, style = MaterialTheme.typography.titleMedium)
            Text(
                row.account.imapUser,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StateChip(row)
    }
}

@Composable
private fun StatusBlock(
    row: AccountRow,
    callbacks: AccountDetailCallbacks,
) {
    val retrying = row.connection == AccountConnectionState.Retrying
    row.account.stateError?.takeIf { row.account.state == "error" }?.let {
        Text(
            if (retrying) stringResource(R.string.account_detail_reconnecting, it) else it,
            color = if (retrying) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            row.lastSync?.let { stringResource(R.string.accounts_last_synced, MailFormat.listDate(it)) }
                ?: stringResource(R.string.accounts_never_synced),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = callbacks.onSyncNow, modifier = Modifier.testTag(AccountDetailTags.SYNC_NOW)) {
            Text(stringResource(R.string.account_detail_sync_now))
        }
    }
    row.sync?.syncTier?.let {
        Text(
            stringResource(R.string.account_detail_sync_tier, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    row.sync?.takeIf { it.errorCount > 0 }?.let {
        Text(
            stringResource(R.string.account_detail_sync_errors, it.errorCount, it.lastError.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    SwitchLine(
        R.string.account_detail_sync_enabled,
        row.account.isActive,
        AccountDetailTags.ACTIVE,
        callbacks.onSetActive,
    )
}

@Composable
private fun Facts(account: AccountResponse) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Fact(R.string.account_detail_user, account.imapUser)
        Fact(R.string.account_detail_server, "${account.imapHost}:${account.imapPort}")
        account.smtpHost?.let {
            Fact(R.string.account_detail_smtp_user, account.smtpUser ?: account.imapUser)
            Fact(R.string.account_detail_smtp_server, "$it:${account.smtpPort}")
        }
        Fact(R.string.account_detail_trash, days(account.trashRetentionDays))
        Fact(R.string.account_detail_junk, days(account.junkRetentionDays))
        Fact(
            R.string.account_detail_glacier,
            when {
                !account.glacierEnabled -> stringResource(R.string.account_detail_off)
                account.glacierAutoDays != null ->
                    stringResource(R.string.account_detail_glacier_auto, account.glacierAutoDays!!)
                else -> stringResource(R.string.account_detail_glacier_manual)
            },
        )
        if (account.glacierAutoDays != null) {
            account.glacierSweepLastRefusal?.let {
                Text(
                    stringResource(R.string.account_detail_glacier_paused, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun days(value: Int?): String =
    if (value != null && value > 0) {
        stringResource(R.string.account_detail_days, value)
    } else {
        stringResource(R.string.account_detail_off)
    }

@Composable
private fun Fact(
    label: Int,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun IdentitiesBlock(
    state: IdentitiesState,
    callbacks: AccountDetailCallbacks,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.identities_title))
        state.errorMessage?.let {
            Text(
                stringResource(R.string.identities_error, it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.hasLoaded && state.identities.isEmpty()) {
            Text(
                stringResource(R.string.identities_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.identities.forEach { identity ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().testTag(AccountDetailTags.identity(identity.id)),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        identity.displayName?.takeIf {
                            it.isNotBlank()
                        } ?: identity.address,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!identity.displayName.isNullOrBlank()) {
                        Text(
                            identity.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (identity.isDefault) {
                        Text(
                            stringResource(R.string.identities_default),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (!identity.isDefault) {
                    TextButton(
                        onClick = { callbacks.onMakeDefault(identity) },
                        modifier = Modifier.testTag(AccountDetailTags.identityDefault(identity.id)),
                    ) { Text(stringResource(R.string.identities_make_default)) }
                }
                TextButton(
                    onClick = { callbacks.onRemoveIdentity(identity) },
                    modifier = Modifier.testTag(AccountDetailTags.identityRemove(identity.id)),
                ) { Text(stringResource(R.string.identities_remove), color = MaterialTheme.colorScheme.error) }
            }
        }
        FormTextField(name, { name = it }, stringResource(R.string.identities_name), AccountDetailTags.IDENTITY_NAME)
        FormTextField(
            address,
            { address = it },
            stringResource(R.string.identities_address),
            AccountDetailTags.IDENTITY_ADDRESS,
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
        )
        OutlinedButton(
            onClick = {
                callbacks.onAddIdentity(name, address) {
                    name = ""
                    address = ""
                }
            },
            enabled = address.isNotBlank(),
            modifier = Modifier.testTag(AccountDetailTags.IDENTITY_ADD),
        ) { Text(stringResource(R.string.identities_add)) }
    }
}

@Composable
private fun EmojiDialog(
    current: String?,
    onDismiss: () -> Unit,
    onSet: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_detail_emoji_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.account_detail_emoji_hint), style = MaterialTheme.typography.bodySmall)
                EMOJI_CHOICES.chunked(EMOJI_PER_ROW).forEach { rowChoices ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowChoices.forEach { emoji ->
                            TextButton(
                                onClick = { onSet(emoji) },
                                modifier = Modifier.testTag(AccountDetailTags.emojiChoice(emoji)),
                            ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        dismissButton = {
            if (current != null) {
                TextButton(onClick = { onSet(null) }, modifier = Modifier.testTag(AccountDetailTags.EMOJI_REMOVE)) {
                    Text(stringResource(R.string.account_detail_emoji_remove))
                }
            }
        },
    )
}

private const val EMOJI_PER_ROW = 5

private val EMOJI_CHOICES: List<String> =
    "🏠 💼 📧 📥 🏢 🎓 🛒 💳 📰 🧳 🎮 🏦 ✈️ 🍽️ 🎵 🛠️ ❤️ ⭐ 🔒 🌱".split(" ")
