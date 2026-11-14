package com.frederikberg.mailverdict.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.search.MessageTarget
import com.frederikberg.mailverdict.kit.session.Connection

/**
 * Search wired to its store, kept for as long as the sign-in lasts so Back from a message finds the
 * results and the position where they were. [accountId] and
 * [folderId] narrow it to where it was opened from; [scopeLabel] names that place in the chip that
 * lifts the narrowing.
 */
@Composable
fun SearchRoute(
    graph: AppGraph,
    connection: Connection,
    accountId: String?,
    folderId: String?,
    scopeLabel: String?,
    onOpenMessage: (MessageTarget) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val store =
        remember(connection, accountId, folderId) {
            graph.searchStore(connection, accountId, folderId, SearchPreferences(context, graph.fixtureMode))
        }
    val state by store.state.collectAsState()

    SearchScreen(
        state = state,
        initialQuery = state.context.query,
        scopeLabel = scopeLabel.takeIf { state.context.accountId != null || state.context.folderIds != null },
        callbacks =
            SearchCallbacks(
                onBack = onBack,
                onQuery = store::queryChanged,
                onContext = store::updateContext,
                onOpen = { onOpenMessage(MessageTarget(it.id, it.accountId, it.folderId)) },
                onLoadMore = store::loadMore,
            ),
    )
}
