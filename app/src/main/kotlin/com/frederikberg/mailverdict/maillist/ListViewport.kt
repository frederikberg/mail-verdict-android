package com.frederikberg.mailverdict.maillist

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import com.frederikberg.mailverdict.kit.maillist.ListIdentity
import com.frederikberg.mailverdict.kit.maillist.ListLanding
import com.frederikberg.mailverdict.kit.maillist.MailRowData
import com.frederikberg.mailverdict.kit.maillist.PagingNeeds
import com.frederikberg.mailverdict.kit.maillist.RowChanges
import com.frederikberg.mailverdict.kit.maillist.ViewportAnchor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * The viewport of one list. It is keyed on the list's identity, so a different list (another filter
 * or grouping, a jump to the newest mail) starts at the top instead of inheriting the offset of the
 * list it replaced, clamped to a different height. Coming back to the same list restores the place
 * by the identity of the row that was at the top, never by a pixel offset.
 */
@Composable
fun rememberMailListState(
    identity: ListIdentity,
    rows: List<MailRowData>,
    saved: ViewportAnchor?,
): LazyListState =
    remember(identity) {
        val index = saved?.let { anchor -> rows.indexOfFirst { it.id == anchor.rowId } } ?: -1
        if (saved != null && index >= 0) LazyListState(index, saved.offsetPx) else LazyListState()
    }

/**
 * Everything that keeps the reader's place while the rows change underneath it. Rows are keyed by id
 * and have one fixed height, so the list itself holds the place of the row at the top across an
 * insertion above it; what is added here is the policy on top: a reader resting at the very top of a
 * window that starts at the newest message is watching for new mail and sees it arrive there, while
 * anyone further down is not moved and is told how many rows landed above.
 *
 * Returns how many rows arrived above the reader since they last were at the top.
 */
@Composable
fun KeepPlaceAcrossChanges(
    listState: LazyListState,
    rows: List<MailRowData>,
    windowAtNewestEdge: Boolean,
): Int {
    val holder = remember(listState) { PlaceHolder() }
    var arrivedAbove by remember(listState) { mutableIntStateOf(0) }
    val ids = rows.map { it.id }
    if (ids != holder.previous) {
        // Composition runs before this change is laid out, so the position read here is the one the reader had.
        val (atTop, anchorId) =
            Snapshot.withoutReadObservation {
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                atTop to
                    listState.layoutInfo.visibleItemsInfo
                        .firstOrNull()
                        ?.key as? String
            }
        val old = holder.previous
        holder.previous = ids
        if (old.isNotEmpty()) {
            if (windowAtNewestEdge && atTop && ids.firstOrNull() != old.firstOrNull()) {
                SideEffect { listState.requestScrollToItem(0, 0) }
            } else {
                val above = RowChanges.newRowsAbove(old, ids, anchorId)
                if (above > 0) SideEffect { arrivedAbove += above }
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .distinctUntilChanged()
            .collect { if (it) arrivedAbove = 0 }
    }
    return arrivedAbove
}

private class PlaceHolder {
    var previous: List<String> = emptyList()
}

/** Asks for the next page while the reader is still two screens from the edge, never at it. */
@Composable
fun PageAhead(
    listState: LazyListState,
    rowCount: Int,
    hasOlder: Boolean,
    hasNewer: Boolean,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
) {
    val total by rememberUpdatedState(rowCount)
    val olderExists by rememberUpdatedState(hasOlder)
    val newerExists by rememberUpdatedState(hasNewer)
    val older by rememberUpdatedState(onLoadOlder)
    val newer by rememberUpdatedState(onLoadNewer)
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo.filter { it.key is String }
            val needs =
                PagingNeeds.of(
                    firstVisible = info.firstOrNull()?.index ?: 0,
                    lastVisible = info.lastOrNull()?.index ?: -1,
                    visibleCount = info.size,
                    total = total,
                )
            // The row count is part of what is emitted: a page that landed and left the reader still near the edge asks for the next one.
            Triple(needs, total, olderExists to newerExists)
        }.collect { (needs, _, _) ->
            if (needs.older && olderExists) older()
            if (needs.newer && newerExists) newer()
        }
    }
}

/** Puts the row a deep link opened a third of the way down the screen, once, and tells the store it did. */
@Composable
fun RevealLanding(
    listState: LazyListState,
    landing: ListLanding?,
    rows: List<MailRowData>,
    onLanded: () -> Unit,
) {
    LaunchedEffect(listState, landing) {
        val reveal = landing as? ListLanding.RevealInUpperThird ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.id == reveal.rowId }
        if (index >= 0) {
            val height = snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
            listState.scrollToItem(index, -height / 3)
        }
        onLanded()
    }
}

/** Reports the row at the top of the viewport and how far into it, for the next visit to this list. */
@Composable
fun ReportViewport(
    listState: LazyListState,
    onViewport: (ViewportAnchor?) -> Unit,
) {
    val report by rememberUpdatedState(onViewport)
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull()
                ?.let { (it.key as? String)?.let { id -> ViewportAnchor(id, -it.offset) } }
        }.distinctUntilChanged().collect { report(it) }
    }
}
