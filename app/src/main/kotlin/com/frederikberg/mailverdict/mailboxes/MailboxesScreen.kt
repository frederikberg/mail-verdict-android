package com.frederikberg.mailverdict.mailboxes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.alerts.AlertBell
import com.frederikberg.mailverdict.contract.FolderKind
import com.frederikberg.mailverdict.kit.live.OfflineNotice
import com.frederikberg.mailverdict.kit.stores.mailboxes.AccountConnectionState
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesAccountSection
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesFolderRow
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesState
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesSupport
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesUnifiedRow
import com.frederikberg.mailverdict.ui.OfflineCapsule

object MailboxesTags {
    const val LIST = "mailboxes-list"
    const val SETTINGS = "mailboxes-settings"
    const val SEARCH = "mailboxes-search"

    fun folder(id: String) = "folder:$id"

    fun unified(id: String) = "unified:$id"

    fun header(key: String) = "header:$key"
}

/**
 * The root screen: the unified views, then one collapsible section per account with its folders and
 * counts. A few dozen uniform rows at most, so every row is composed and the list only has to keep
 * its identity: rows are keyed by id, so a count changing under the reader moves nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxesScreen(
    state: MailboxesState,
    refreshing: Boolean,
    offline: OfflineNotice?,
    onRefresh: () -> Unit,
    onToggleCollapsed: (String) -> Unit,
    onOpenFolder: (MailboxesFolderRow) -> Unit,
    onOpenUnified: (MailboxesUnifiedRow) -> Unit,
    onOpenSettings: () -> Unit,
    alertBadge: Int = 0,
    onOpenSearch: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.mailboxes_title)) },
                actions = {
                    IconButton(onClick = onOpenSearch, modifier = Modifier.testTag(MailboxesTags.SEARCH)) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_title))
                    }
                    AlertBell(alertBadge, onOpenAlerts)
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag(MailboxesTags.SETTINGS)) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.mailboxes_settings))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag(MailboxesTags.LIST),
                ) {
                    banner(state, onRefresh)
                    unifiedSection(state, onToggleCollapsed, onOpenUnified)
                    state.accountSections.forEach { accountSection(it, state, onToggleCollapsed, onOpenFolder) }
                    if (!state.hasAnyAccount) {
                        item(key = "no-accounts") {
                            Text(
                                stringResource(R.string.mailboxes_no_accounts),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            if (offline != null) {
                OfflineCapsule(offline, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
        }
    }
}

private fun LazyListScope.banner(
    state: MailboxesState,
    onRetry: () -> Unit,
) {
    state.deadOutboxBanner?.let { text ->
        item(key = "dead-outbox") {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
    state.loadError?.let { error ->
        item(key = "load-error") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.mailboxes_load_error, error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.mailboxes_retry)) }
            }
        }
    }
}

private fun LazyListScope.unifiedSection(
    state: MailboxesState,
    onToggleCollapsed: (String) -> Unit,
    onOpen: (MailboxesUnifiedRow) -> Unit,
) {
    val collapsed = state.isCollapsed(MailboxesSupport.UNIFIED_KEY)
    item(key = MailboxesTags.header(MailboxesSupport.UNIFIED_KEY)) {
        SectionHeader(
            collapsed,
            { onToggleCollapsed(MailboxesSupport.UNIFIED_KEY) },
            Modifier.testTag(MailboxesTags.header(MailboxesSupport.UNIFIED_KEY)),
        ) {
            Text(stringResource(R.string.mailboxes_unified), style = MaterialTheme.typography.titleSmall)
        }
    }
    if (collapsed) return
    if (state.unifiedRows.isEmpty() && !state.isLoading && state.loadError == null) {
        item(key = "no-unified") {
            Text(
                stringResource(R.string.mailboxes_no_unified),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
    items(state.unifiedRows, key = { MailboxesTags.unified(it.id) }) { row ->
        RowLine(
            leading = { Text(row.emoji ?: "📥") },
            title = row.name,
            trailing = row.contributingAccountEmojis.takeIf { it.size > 1 }?.joinToString(""),
            badge = row.unreadCount,
            modifier = Modifier.testTag(MailboxesTags.unified(row.id)).clickable { onOpen(row) },
        )
    }
}

private fun LazyListScope.accountSection(
    section: MailboxesAccountSection,
    state: MailboxesState,
    onToggleCollapsed: (String) -> Unit,
    onOpenFolder: (MailboxesFolderRow) -> Unit,
) {
    val collapsed = state.isCollapsed(section.collapseKey)
    item(key = MailboxesTags.header(section.collapseKey)) {
        SectionHeader(
            collapsed,
            { onToggleCollapsed(section.collapseKey) },
            Modifier.testTag(MailboxesTags.header(section.collapseKey)),
        ) {
            section.emoji?.let { Text("$it ") }
            Text(section.name, style = MaterialTheme.typography.titleSmall)
            when (section.connectionState) {
                AccountConnectionState.Ok -> Unit
                AccountConnectionState.Retrying -> Chip(stringResource(R.string.mailboxes_chip_retrying), false)
                AccountConnectionState.NeverConnected -> Chip(stringResource(R.string.mailboxes_chip_error), true)
            }
        }
    }
    if (collapsed) return
    if (section.connectionState == AccountConnectionState.NeverConnected) {
        item(key = "never-connected:${section.id}") {
            Text(
                section.stateError ?: stringResource(R.string.mailboxes_account_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        return
    }
    items(section.folders, key = { MailboxesTags.folder(it.id) }) { folder ->
        RowLine(
            leading = { Text(folderGlyph(folder)) },
            title = folder.displayName,
            trailing = null,
            badge = folder.badgeCount,
            modifier = Modifier.testTag(MailboxesTags.folder(folder.id)).clickable { onOpenFolder(folder) },
        )
    }
}

private fun folderGlyph(folder: MailboxesFolderRow): String =
    when {
        folder.kind == FolderKind.Glacier -> "❄️"
        folder.specialUse == "inbox" -> "📥"
        folder.specialUse == "drafts" -> "📝"
        folder.specialUse == "sent" -> "📤"
        folder.specialUse == "archive" -> "🗄️"
        folder.specialUse == "junk" -> "🚫"
        folder.specialUse == "trash" -> "🗑️"
        else -> "📁"
    }

@Composable
private fun SectionHeader(
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
) {
    Column(modifier) {
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClick = onToggle,
                    ).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) { title() }
            Icon(
                if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription =
                    stringResource(if (collapsed) R.string.mailboxes_expand else R.string.mailboxes_collapse),
            )
        }
    }
}

@Composable
private fun RowLine(
    leading: @Composable () -> Unit,
    title: String,
    trailing: String?,
    badge: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        leading()
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        trailing?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        Box(Modifier.weight(1f))
        if (badge > 0) Chip("$badge", false)
    }
}

@Composable
private fun Chip(
    text: String,
    error: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (error) colors.errorContainer else colors.secondaryContainer,
        modifier = Modifier.padding(start = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (error) colors.onErrorContainer else colors.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
