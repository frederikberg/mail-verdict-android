package com.frederikberg.mailverdict.search

import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.search.ApiSearchBackend
import com.frederikberg.mailverdict.kit.search.SearchContext
import com.frederikberg.mailverdict.kit.search.SearchStore
import com.frederikberg.mailverdict.kit.session.Connection

private data class SearchKey(
    val accountId: String?,
    val folderId: String?,
)

/**
 * The search of [connection] opened from [accountId] / [folderId] (or from nowhere), kept for as long
 * as the sign-in lasts. How the person likes to search starts from what they last used.
 */
fun AppGraph.searchStore(
    connection: Connection,
    accountId: String?,
    folderId: String?,
    preferences: SearchPreferences,
): SearchStore =
    connectionStores.get(connection, SearchKey(accountId, folderId)) { scope ->
        val saved = preferences.load() ?: SearchContext()
        SearchStore(
            backend = ApiSearchBackend(connection.api),
            scope = scope,
            persistence = preferences,
            initial = saved.copy(accountId = accountId, folderIds = folderId?.let { listOf(it) }),
        ).also { it.follow(connection.liveEventHub.events) }
    }
