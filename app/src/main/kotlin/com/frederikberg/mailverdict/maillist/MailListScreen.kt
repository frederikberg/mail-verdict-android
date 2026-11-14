package com.frederikberg.mailverdict.maillist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.kit.live.OfflineNotice
import com.frederikberg.mailverdict.kit.maillist.ListPhase
import com.frederikberg.mailverdict.kit.maillist.MailListState
import com.frederikberg.mailverdict.kit.maillist.MailRowData
import com.frederikberg.mailverdict.kit.maillist.ViewportAnchor
import com.frederikberg.mailverdict.ui.OfflineCapsule
import kotlinx.coroutines.launch

object MailListTags {
    const val LIST = "maillist-list"
    const val UNREAD_FILTER = "maillist-unread-filter"
    const val MORE = "maillist-more"
    const val THREADED = "maillist-threaded"
    const val NEW_CAPSULE = "maillist-new-capsule"
    const val ARRIVALS_CAPSULE = "maillist-arrivals-capsule"
    const val RETRY = "maillist-retry"
    const val SELECT_LOADED = "maillist-select-loaded"
    const val STOP_SELECTING = "maillist-stop-selecting"
    const val EMPTY = "maillist-empty"

    fun row(id: String) = "mail:$id"
}

/** What a row's slots measure, derived from the text styles and the device's font scale so a row has one height whatever it holds. */
data class RowMetrics(
    val senderLine: Dp,
    val subjectLine: Dp,
    val snippetLine: Dp,
    val verticalPadding: Dp,
) {
    val snippetHeight: Dp get() = snippetLine * SNIPPET_LINES
    val rowHeight: Dp get() = verticalPadding * 2 + senderLine + subjectLine + snippetHeight

    /** What the list lays out per row: the content and the divider under it. */
    val itemHeight: Dp get() = rowHeight + DIVIDER

    companion object {
        const val SNIPPET_LINES = 2
        val DIVIDER = 1.dp
    }
}

@Composable
fun rememberRowMetrics(): RowMetrics {
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    return remember(typography, density) {
        fun TextStyle.line(): Dp =
            with(density) {
                (if (lineHeight.isSpecified) lineHeight else fontSize * 1.25f).toDp()
            }
        RowMetrics(
            senderLine = SENDER_STYLE(typography).line(),
            subjectLine = SUBJECT_STYLE(typography).line(),
            snippetLine = SNIPPET_STYLE(typography).line(),
            verticalPadding = 10.dp,
        )
    }
}

private val SENDER_STYLE: (androidx.compose.material3.Typography) -> TextStyle = { it.titleSmall }
private val SUBJECT_STYLE: (androidx.compose.material3.Typography) -> TextStyle = { it.bodyMedium }
private val SNIPPET_STYLE: (androidx.compose.material3.Typography) -> TextStyle = { it.bodySmall }

/**
 * A folder's or unified view's messages. The rows have one height each, computed from the type
 * metrics before layout, so a row's position is arithmetic and nothing above the reader is ever
 * measured late. [listState] is hoisted so the owner decides when this is a different list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MailListScreen(
    title: String,
    state: MailListState,
    listState: LazyListState,
    offline: OfflineNotice?,
    refreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRow: (MailRowData) -> Unit,
    onLongPressRow: (MailRowData) -> Unit,
    onToggleSelection: (MailRowData) -> Unit,
    onUnreadOnly: (Boolean) -> Unit,
    onThreaded: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    onJumpToLatest: () -> Unit,
    onSelectLoaded: () -> Unit,
    onStopSelecting: () -> Unit,
    onLanded: () -> Unit,
    onViewport: (ViewportAnchor?) -> Unit,
    onDismissNotice: () -> Unit,
    selectionActions: @Composable RowScope.() -> Unit = {},
) {
    val metrics = rememberRowMetrics()
    val scope = rememberCoroutineScope()
    val arrivedAbove =
        rememberRowsArrivedAbove(listState, state.rows, state.windowAtNewestEdge)
    PageAhead(listState, state.rows.size, state.hasOlder, state.hasNewer, onLoadOlder, onLoadNewer)
    RevealLanding(listState, state.landing, state.rows, onLanded)
    ReportViewport(listState, onViewport)

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            onDismissNotice()
        }
    }

    Scaffold(
        topBar = {
            if (state.isSelecting) {
                SelectionBar(state.selectedIds.size, onSelectLoaded, onStopSelecting, selectionActions)
            } else {
                ListBar(title, state, onBack, onUnreadOnly, onThreaded)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val phase = state.phase) {
                ListPhase.Loading -> CenteredProgress()
                is ListPhase.Failed -> FailureView(phase.message, onRetry)
                ListPhase.Loaded ->
                    if (state.rows.isEmpty()) {
                        Text(
                            state.emptyMessage,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp).testTag(MailListTags.EMPTY),
                        )
                    } else {
                        PullToRefreshBox(
                            isRefreshing = refreshing,
                            onRefresh = onRefresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().testTag(MailListTags.LIST),
                            ) {
                                items(state.rows, key = { it.id }, contentType = { ROW }) { row ->
                                    MailRow(
                                        row = row,
                                        metrics = metrics,
                                        selecting = state.isSelecting,
                                        selected = row.id in state.selectedIds,
                                        onClick = { if (state.isSelecting) onToggleSelection(row) else onOpenRow(row) },
                                        onLongClick = {
                                            if (state.isSelecting) {
                                                onToggleSelection(
                                                    row,
                                                )
                                            } else {
                                                onLongPressRow(row)
                                            }
                                        },
                                    )
                                }
                                if (state.isLoadingOlder) {
                                    item(key = FOOTER_KEY, contentType = FOOTER) {
                                        Box(
                                            Modifier.fillMaxWidth().height(56.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (offline != null) OfflineCapsule(offline)
                if (state.hasNewer && state.pendingArrivals > 0) {
                    Capsule(
                        stringResource(R.string.maillist_new_messages, state.pendingArrivals),
                        onJumpToLatest,
                        Modifier.padding(top = 8.dp).testTag(MailListTags.ARRIVALS_CAPSULE),
                    )
                } else if (arrivedAbove > 0) {
                    Capsule(
                        stringResource(R.string.maillist_new_messages, arrivedAbove),
                        { scope.launch { listState.scrollToItem(0) } },
                        Modifier.padding(top = 8.dp).testTag(MailListTags.NEW_CAPSULE),
                    )
                }
            }
        }
    }
}

private const val ROW = "row"
private const val FOOTER = "footer"
private const val FOOTER_KEY = "footer-loading-older"

@Composable
private fun Capsule(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListBar(
    title: String,
    state: MailListState,
    onBack: () -> Unit,
    onUnreadOnly: (Boolean) -> Unit,
    onThreaded: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle =
                    if (state.unreadOnly) {
                        stringResource(
                            R.string.maillist_unread_subtitle,
                        )
                    } else {
                        state.countLabel
                    }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = {
            IconToggleButton(
                checked = state.unreadOnly,
                onCheckedChange = onUnreadOnly,
                modifier = Modifier.testTag(MailListTags.UNREAD_FILTER),
            ) {
                Icon(Icons.Default.Email, contentDescription = stringResource(R.string.maillist_unread_filter))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(MailListTags.MORE)) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.maillist_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.maillist_threaded)) },
                        leadingIcon = { if (state.threaded) Icon(Icons.Default.Check, contentDescription = null) },
                        onClick = {
                            menu = false
                            onThreaded(!state.threaded)
                        },
                        modifier = Modifier.testTag(MailListTags.THREADED),
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    onSelectLoaded: () -> Unit,
    onStop: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.maillist_selected, count)) },
        navigationIcon = {
            IconButton(onClick = onStop, modifier = Modifier.testTag(MailListTags.STOP_SELECTING)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.maillist_stop_selecting))
            }
        },
        actions = {
            TextButton(onClick = onSelectLoaded, modifier = Modifier.testTag(MailListTags.SELECT_LOADED)) {
                Text(stringResource(R.string.maillist_select_loaded))
            }
            actions()
        },
    )
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun FailureView(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.maillist_load_failed, message), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, modifier = Modifier.testTag(MailListTags.RETRY)) {
            Text(stringResource(R.string.maillist_retry))
        }
    }
}

/**
 * One row at a fixed height. Each line is a slot of its own height, so what a row holds can never
 * change how tall it is: text longer than its slot is ellipsized at the slot's line limit and
 * short text leaves the slot empty.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MailRow(
    row: MailRowData,
    metrics: RowMetrics,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val unreadWeight = if (row.isUnread) FontWeight.Bold else FontWeight.Normal
    Column(modifier.height(metrics.itemHeight).testTag(MailListTags.row(row.id))) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(metrics.rowHeight)
                    .background(if (selected) colors.secondaryContainer else colors.surface)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(horizontal = 16.dp, vertical = metrics.verticalPadding)
                    .clipToBounds(),
        ) {
            Box(Modifier.width(24.dp).height(metrics.senderLine), contentAlignment = Alignment.CenterStart) {
                when {
                    selecting && selected -> Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                    row.isUnread ->
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(colors.primary)
                                .semantics { contentDescription = "unread" },
                        )
                }
            }
            Column(Modifier.weight(1f)) {
                Line(metrics.senderLine) {
                    Text(
                        row.sender,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = unreadWeight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        row.date,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (row.isUnread) colors.primary else colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Line(metrics.subjectLine) {
                    Text(
                        row.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (row.isUnread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Badges(row)
                }
                Box(Modifier.fillMaxWidth().height(metrics.snippetHeight).clipToBounds()) {
                    Text(
                        row.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = RowMetrics.SNIPPET_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(thickness = RowMetrics.DIVIDER)
    }
}

@Composable
private fun Line(
    height: Dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun Badges(row: MailRowData) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall
    if (row.isSpam) Text(" SPAM", style = style, color = colors.error, maxLines = 1)
    if (row.isFlagged) Text(" ⚑", style = style, color = colors.tertiary, maxLines = 1)
    if (row.hasAttachments) Text(" 📎", style = style, maxLines = 1)
    if (row.threadCount > 1) {
        Text(
            " ${row.threadCount}",
            style = style,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = "${row.threadCount} messages" },
        )
    }
}
