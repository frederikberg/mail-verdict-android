package com.frederikberg.mailverdict.maillist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.actions.ActionMenuItems
import com.frederikberg.mailverdict.actions.ActionTags
import com.frederikberg.mailverdict.actions.ConfirmDeleteForeverDialog
import com.frederikberg.mailverdict.actions.MovePickerSheet
import com.frederikberg.mailverdict.kit.actions.ActionGroup
import com.frederikberg.mailverdict.kit.actions.MessageActionSet
import com.frederikberg.mailverdict.kit.actions.MoveTarget
import com.frederikberg.mailverdict.kit.actions.UiAction
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
    val queue by area.actions.state.collectAsState()
    var moving by remember { mutableStateOf<Set<String>?>(null) }
    var moveTargets by remember { mutableStateOf<List<MoveTarget>?>(null) }
    var confirmForever by remember { mutableStateOf<Set<String>?>(null) }

    LaunchedEffect(store) {
        when (scope) {
            is ListScope.Folder -> area.recent.recordFolderView()
            is ListScope.Unified -> area.recent.recordUnifiedView(UnifiedViewRef(scope.viewId, scope.name))
        }
        store.start()
    }

    // The folders a move can go to are read when the picker opens.
    LaunchedEffect(moving) {
        val ids = moving ?: return@LaunchedEffect
        moveTargets = null
        val account = store.accountsOf(ids).singleOrNull()
        moveTargets = account?.let { runCatching { area.moveTargets(it) }.getOrDefault(emptyList()) }.orEmpty()
    }

    fun run(
        action: UiAction,
        ids: Set<String>,
    ) {
        when (action) {
            UiAction.MoveTo -> moving = ids
            UiAction.DeleteForever -> confirmForever = ids
            else -> store.perform(action, ids)
        }
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
        selectionActions = {
            val ids = state.selectedIds
            val context = store.actionContext(ids)
            val skip = if (store.accountsOf(ids).size > 1) setOf(UiAction.MoveTo) else emptySet()
            SelectionActions(MessageActionSet.groups(context), skip) { run(it, ids) }
        },
        actions =
            ListActions(
                notices = area.actions.notices,
                attention = queue.summary,
                swipeEnd = { MessageActionSet.swipeEnd(store.actionContext(setOf(it.id))) },
                swipeStart = { MessageActionSet.swipeStart(store.actionContext(setOf(it.id))) },
                onSwipe = { row, action -> run(action, setOf(row.id)) },
                onUndo = area.actions::undo,
                onRetryAction = { area.actions.retry(listOf(it)) },
            ),
    )

    moving?.let { ids ->
        val account = store.accountsOf(ids).singleOrNull()
        val folders = store.foldersOf(ids)
        MovePickerSheet(
            targets = moveTargets,
            excludingFolderIds = if (folders.size == 1) folders else emptySet(),
            recentIds = account?.let(area.recentMoves::recentIds).orEmpty(),
            onPick = { target ->
                area.recentMoves.record(target.accountId, target.folderId)
                store.perform(UiAction.MoveTo, ids, target.folderId)
                moving = null
            },
            onDismiss = { moving = null },
        )
    }
    confirmForever?.let { ids ->
        ConfirmDeleteForeverDialog(
            count = ids.size,
            onConfirm = {
                store.perform(UiAction.DeleteForever, ids)
                confirmForever = null
            },
            onDismiss = { confirmForever = null },
        )
    }
}

/** Delete at hand, the rest of what the selection allows behind a menu. */
@Composable
fun RowScope.SelectionActions(
    groups: List<ActionGroup>,
    skip: Set<UiAction>,
    onAction: (UiAction) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val destructive = groups.firstOrNull { it.kind == ActionGroup.Kind.Destructive }?.actions?.firstOrNull()
    if (destructive != null) {
        IconButton(onClick = { onAction(destructive) }, modifier = Modifier.testTag(ActionTags.menu(destructive))) {
            Icon(Icons.Default.Delete, contentDescription = destructive.title)
        }
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(MailListTags.SELECTION_MENU)) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.maillist_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ActionMenuItems(
                groups = groups.filter { it.kind != ActionGroup.Kind.Destructive },
                skip = skip,
                onAction = {
                    open = false
                    onAction(it)
                },
            )
        }
    }
}
