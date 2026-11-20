package com.frederikberg.mailverdict.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.kit.accounts.AccountRow
import com.frederikberg.mailverdict.kit.format.MailFormat
import com.frederikberg.mailverdict.kit.rules.Part
import com.frederikberg.mailverdict.kit.rules.RuleGroupView
import com.frederikberg.mailverdict.kit.rules.RuleRow
import com.frederikberg.mailverdict.kit.rules.RuleSummary
import com.frederikberg.mailverdict.kit.rules.RulesState
import com.frederikberg.mailverdict.kit.rules.RulesView
import com.frederikberg.mailverdict.ui.FormTextField
import com.frederikberg.mailverdict.ui.SectionTitle

object RulesTags {
    const val LIST = "rules-list"
    const val FILTER = "rules-filter"
    const val MENU = "rules-menu"
    const val MENU_PAUSE = "rules-menu-pause"
    const val MENU_RESUME = "rules-menu-resume"
    const val MENU_HISTORY = "rules-menu-history"
    const val PAUSED_BANNER = "rules-paused-banner"
    const val RESUME = "rules-resume"
    const val CONFIRM_PAUSE = "rules-confirm-pause"
    const val CONFIRM_DELETE = "rules-confirm-delete"
    const val CONFIRM_RESTORE = "rules-confirm-restore"
    const val ERROR = "rules-error"
    const val EMPTY = "rules-empty"
    const val NO_MATCH = "rules-no-match"
    const val HISTORY = "rules-history"
    const val HISTORY_CLOSE = "rules-history-close"

    fun group(name: String) = "rules-group:$name"

    fun rule(id: String) = "rule:$id"

    fun toggle(id: String) = "rule-toggle:$id"

    fun actions(id: String) = "rule-actions:$id"

    fun moveUp(id: String) = "rule-up:$id"

    fun moveDown(id: String) = "rule-down:$id"

    fun delete(id: String) = "rule-delete:$id"

    fun spam(accountId: String) = "rules-spam:$accountId"

    fun orders(accountId: String) = "rules-orders:$accountId"

    fun restore(revision: Int) = "rules-restore:$revision"
}

class RulesCallbacks(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onToggle: (RuleRow, Boolean) -> Unit,
    val onMove: (RuleRow, Int) -> Unit,
    val onDelete: (RuleRow) -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onRestore: (Int) -> Unit,
    val onSetSpam: (accountId: String, Boolean) -> Unit,
    val onSetOrders: (accountId: String, Boolean) -> Unit,
)

/**
 * The person's rules as sentences, grouped by what they do, each with a switch and a menu, above the
 * per-account spam and orders switches they are the visible face of. Writing a rule is on the web;
 * here a rule is switched, moved, removed, and the whole list paused or restored from its history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    state: RulesState,
    view: RulesView?,
    filter: String,
    onFilter: (String) -> Unit,
    accounts: List<AccountRow>,
    snackbar: SnackbarHostState,
    callbacks: RulesCallbacks,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmingPause by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    val deleting = view?.groups?.flatMap { it.rows }?.firstOrNull { it.id == deleteTarget }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (view != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(RulesTags.MENU)) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.rules_menu))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rules_history)) },
                                    onClick = {
                                        menuOpen = false
                                        historyOpen = true
                                        callbacks.onOpenHistory()
                                    },
                                    modifier = Modifier.testTag(RulesTags.MENU_HISTORY),
                                )
                                if (view.paused) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rules_resume)) },
                                        onClick = {
                                            menuOpen = false
                                            callbacks.onResume()
                                        },
                                        modifier = Modifier.testTag(RulesTags.MENU_RESUME),
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rules_pause)) },
                                        onClick = {
                                            menuOpen = false
                                            confirmingPause = true
                                        },
                                        modifier = Modifier.testTag(RulesTags.MENU_PAUSE),
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.errorMessage?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 16.dp,
                                vertical = 8.dp,
                            ).testTag(RulesTags.ERROR),
                ) {
                    Text(
                        stringResource(R.string.rules_error, it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = callbacks.onRetry) { Text(stringResource(R.string.rules_retry)) }
                }
            }
            if (view == null) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        Modifier.padding(24.dp).align(Alignment.CenterHorizontally),
                    )
                }
            } else {
                RulesList(view, filter, onFilter, accounts, callbacks, onAskDelete = { deleteTarget = it.id })
            }
        }
    }

    if (confirmingPause) {
        AlertDialog(
            onDismissRequest = { confirmingPause = false },
            title = { Text(stringResource(R.string.rules_pause_title)) },
            text = { Text(stringResource(R.string.rules_pause_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingPause = false
                        callbacks.onPause()
                    },
                    modifier = Modifier.testTag(RulesTags.CONFIRM_PAUSE),
                ) { Text(stringResource(R.string.rules_pause_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingPause = false },
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.rules_delete_title, deleting.stage.name)) },
            text = { Text(stringResource(R.string.rules_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        callbacks.onDelete(deleting)
                    },
                    modifier = Modifier.testTag(RulesTags.CONFIRM_DELETE),
                ) { Text(stringResource(R.string.rules_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (historyOpen) {
        HistoryDialog(state, view, onClose = { historyOpen = false }, onAskRestore = { restoreTarget = it })
    }
    restoreTarget?.let { revision ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text(stringResource(R.string.rules_restore_title, revision)) },
            text = { Text(stringResource(R.string.rules_restore_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreTarget = null
                        historyOpen = false
                        callbacks.onRestore(revision)
                    },
                    modifier = Modifier.testTag(RulesTags.CONFIRM_RESTORE),
                ) { Text(stringResource(R.string.rules_history_restore)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { restoreTarget = null },
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun RulesList(
    view: RulesView,
    filter: String,
    onFilter: (String) -> Unit,
    accounts: List<AccountRow>,
    callbacks: RulesCallbacks,
    onAskDelete: (RuleRow) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().testTag(RulesTags.LIST)) {
        item(key = "intro") {
            Text(
                stringResource(R.string.rules_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        if (view.paused) {
            item(key = "paused") {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth().testTag(RulesTags.PAUSED_BANNER),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.rules_paused_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = callbacks.onResume, modifier = Modifier.testTag(RulesTags.RESUME)) {
                            Text(stringResource(R.string.rules_resume_button))
                        }
                    }
                }
            }
        }
        item(key = "features") { Features(accounts, callbacks) }
        item(key = "filter") {
            FormTextField(
                filter,
                onFilter,
                stringResource(R.string.rules_filter),
                RulesTags.FILTER,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (view.ruleCount == 0) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.rules_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp).testTag(RulesTags.EMPTY),
                )
            }
        } else if (!view.hasMatches) {
            item(key = "no-match") {
                Text(
                    stringResource(R.string.rules_no_match, filter),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp).testTag(RulesTags.NO_MATCH),
                )
            }
        }
        view.groups.filter { it.rows.isNotEmpty() }.forEach { group ->
            item(key = "group:${group.group.name}") { GroupHeader(group) }
            items(group.rows, key = { RulesTags.rule(it.id) }) { row ->
                RuleLine(row, callbacks, onAskDelete)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun GroupHeader(group: RuleGroupView) {
    SectionTitle(
        stringResource(R.string.rules_group_count, group.group.label, group.total),
        modifier =
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                .testTag(RulesTags.group(group.group.name)),
    )
}

@Composable
private fun Features(
    accounts: List<AccountRow>,
    callbacks: RulesCallbacks,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(R.string.rules_features_title))
        Text(
            stringResource(R.string.rules_features_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (accounts.isEmpty()) {
            Text(stringResource(R.string.rules_features_none), style = MaterialTheme.typography.bodyMedium)
        }
        accounts.forEach { row ->
            Column {
                Text(
                    "${row.account.emoji?.let { "$it " }.orEmpty()}${row.account.name}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(
                        checked = row.account.spamEnabled,
                        onCheckedChange = { callbacks.onSetSpam(row.id, it) },
                        modifier = Modifier.testTag(RulesTags.spam(row.id)),
                    )
                    Text(stringResource(R.string.rules_feature_spam), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = row.account.ordersEnabled,
                        onCheckedChange = { callbacks.onSetOrders(row.id, it) },
                        modifier = Modifier.padding(start = 8.dp).testTag(RulesTags.orders(row.id)),
                    )
                    Text(stringResource(R.string.rules_feature_orders), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun RuleLine(
    row: RuleRow,
    callbacks: RulesCallbacks,
    onAskDelete: (RuleRow) -> Unit,
) {
    var menuOpen by rememberSaveable(row.id) { mutableStateOf(false) }
    val muted = !row.stage.enabled
    val name = row.stage.name
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = 10.dp,
                ).testTag(RulesTags.rule(row.id)),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                textDecoration = if (muted) TextDecoration.LineThrough else null,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                sentence(row.summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.scope,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.summary.stop) Badge(stringResource(R.string.rules_stop), error = false)
                if (row.summary.permanent) Badge(stringResource(R.string.rules_permanent), error = true)
            }
            row.warnings.forEach {
                Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Switch(
            checked = row.stage.enabled,
            onCheckedChange = { callbacks.onToggle(row, it) },
            modifier = Modifier.testTag(RulesTags.toggle(row.id)),
        )
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(RulesTags.actions(row.id))) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.rules_actions, name))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rules_move_up)) },
                    enabled = row.canMoveUp,
                    onClick = {
                        menuOpen = false
                        callbacks.onMove(row, -1)
                    },
                    modifier = Modifier.testTag(RulesTags.moveUp(row.id)),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rules_move_down)) },
                    enabled = row.canMoveDown,
                    onClick = {
                        menuOpen = false
                        callbacks.onMove(row, 1)
                    },
                    modifier = Modifier.testTag(RulesTags.moveDown(row.id)),
                )
                if (row.canDelete) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rules_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onAskDelete(row)
                        },
                        modifier = Modifier.testTag(RulesTags.delete(row.id)),
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(
    text: String,
    error: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (error) colors.errorContainer else colors.secondaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (error) colors.onErrorContainer else colors.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** "If <condition> → <actions>", or the fixed sentence of a built-in step, with what the rule is about in bold. */
@Composable
private fun sentence(summary: RuleSummary): AnnotatedString {
    val prefix = stringResource(R.string.rules_if)
    return buildAnnotatedString {
        fun parts(list: List<Part>) =
            list.forEach { part ->
                if (part.strong) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(part.text) }
                } else {
                    append(part.text)
                }
            }
        if (summary.thenParts.isEmpty()) {
            parts(summary.whenParts)
        } else {
            append("$prefix ")
            parts(summary.whenParts)
            append(" → ")
            parts(summary.thenParts)
        }
    }
}

@Composable
private fun HistoryDialog(
    state: RulesState,
    view: RulesView?,
    onClose: () -> Unit,
    onAskRestore: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.rules_history_title)) },
        text = {
            val revisions = state.revisions
            when {
                revisions == null -> CircularProgressIndicator()
                revisions.isEmpty() -> Text(stringResource(R.string.rules_history_empty))
                else ->
                    LazyColumn(Modifier.testTag(RulesTags.HISTORY)) {
                        items(revisions, key = { it.revision }) { revision ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.rules_history_item, revision.revision))
                                    Text(
                                        MailFormat.fullDate(revision.createdAt) + " · " +
                                            (revision.note ?: stringResource(R.string.rules_history_note_none)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (revision.revision == view?.revision) {
                                    Text(
                                        stringResource(R.string.rules_history_current),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                } else {
                                    TextButton(
                                        onClick = { onAskRestore(revision.revision) },
                                        modifier = Modifier.testTag(RulesTags.restore(revision.revision)),
                                    ) { Text(stringResource(R.string.rules_history_restore)) }
                                }
                            }
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.testTag(RulesTags.HISTORY_CLOSE)) {
                Text(stringResource(R.string.rules_close))
            }
        },
    )
}
