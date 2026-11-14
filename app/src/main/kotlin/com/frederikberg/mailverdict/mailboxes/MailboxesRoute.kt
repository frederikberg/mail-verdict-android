package com.frederikberg.mailverdict.mailboxes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.live.OfflineNotice
import com.frederikberg.mailverdict.kit.session.Connection
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesFolderRow
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesStore
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesUnifiedRow
import kotlinx.coroutines.launch

/**
 * The overview wired to its store: one store per connection, live while the screen is on, health
 * polled only while it is visible.
 */
@Composable
fun MailboxesRoute(
    graph: AppGraph,
    connection: Connection,
    onOpenFolder: (MailboxesFolderRow) -> Unit,
    onOpenUnified: (MailboxesUnifiedRow) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val store =
        remember(connection) {
            MailboxesStore(connection.api, graph.appScope, graph.preferences).also {
                it.follow(connection.liveEventHub.events, connection.liveEventHub.pathRestored)
            }
        }
    val state by store.state.collectAsState()
    val reachability by connection.reachability.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(store) { store.load() }
    DisposableEffect(store) {
        store.startSyncStatusPolling()
        onDispose { store.stopSyncStatusPolling() }
    }

    MailboxesScreen(
        state = state,
        refreshing = refreshing,
        offline = OfflineNotice.make(reachability),
        onRefresh = {
            scope.launch {
                refreshing = true
                store.load()
                refreshing = false
            }
        },
        onToggleCollapsed = store::toggleCollapsed,
        onOpenFolder = onOpenFolder,
        onOpenUnified = onOpenUnified,
        onOpenSettings = onOpenSettings,
    )
}
