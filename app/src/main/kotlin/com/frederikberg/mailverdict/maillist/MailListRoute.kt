package com.frederikberg.mailverdict.maillist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.live.OfflineNotice
import com.frederikberg.mailverdict.kit.maillist.ListScope
import com.frederikberg.mailverdict.kit.maillist.MailRowData
import com.frederikberg.mailverdict.kit.reader.UnifiedViewRef
import com.frederikberg.mailverdict.kit.session.Connection
import kotlinx.coroutines.launch

/** The list wired to its store. The store outlives this screen, so the reader and back find the rows, the window and the place where they were. */
@Composable
fun MailListRoute(
    graph: AppGraph,
    connection: Connection,
    scope: ListScope,
    title: String,
    aroundMessageId: String?,
    onBack: () -> Unit,
    onOpenMessage: (row: MailRowData, folderIds: Set<String>) -> Unit,
) {
    val area = remember(connection) { graph.mailArea(connection) }
    val store = remember(area, scope, aroundMessageId) { area.list(scope, aroundMessageId) }
    val state by store.state.collectAsState()
    val reachability by connection.reachability.collectAsState()
    val listState = rememberMailListState(state.identity, state.rows, store.savedViewport)
    val coroutines = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        when (scope) {
            is ListScope.Folder -> area.recent.recordFolderView()
            is ListScope.Unified -> area.recent.recordUnifiedView(UnifiedViewRef(scope.viewId, scope.name))
        }
        store.start()
    }

    MailListScreen(
        title = title,
        state = state,
        listState = listState,
        offline = OfflineNotice.make(reachability, state.savedCopyAtMs),
        refreshing = refreshing,
        onBack = onBack,
        onRefresh = {
            coroutines.launch {
                refreshing = true
                store.pullToRefresh()
                refreshing = false
            }
        },
        onOpenRow = { row ->
            store.didOpen(row.id)
            val folders =
                when (scope) {
                    is ListScope.Folder -> setOf(scope.folderId)
                    is ListScope.Unified -> state.context.memberFolderIds.ifEmpty { setOf(row.folderId) }
                }
            onOpenMessage(row, folders)
        },
        onLongPressRow = { store.startSelecting(it.id) },
        onToggleSelection = { store.toggleSelection(it.id) },
        onUnreadOnly = { coroutines.launch { store.setUnreadOnly(it) } },
        onThreaded = { coroutines.launch { store.setThreaded(it) } },
        onRetry = { coroutines.launch { store.retry() } },
        onLoadOlder = { coroutines.launch { store.loadOlder() } },
        onLoadNewer = { coroutines.launch { store.loadNewer() } },
        onJumpToLatest = { coroutines.launch { store.jumpToLatest() } },
        onSelectLoaded = store::selectLoaded,
        onStopSelecting = store::stopSelecting,
        onLanded = { store.consumeLanding() },
        onViewport = { anchor -> anchor?.let(store::saveViewport) },
        onDismissNotice = store::dismissNotice,
    )
}
