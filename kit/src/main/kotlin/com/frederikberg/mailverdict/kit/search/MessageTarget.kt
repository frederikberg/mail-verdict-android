package com.frederikberg.mailverdict.kit.search

import com.frederikberg.mailverdict.kit.api.listFolders
import com.frederikberg.mailverdict.kit.api.listUnifiedFolders
import com.frederikberg.mailverdict.kit.format.folderDisplayName
import com.frederikberg.mailverdict.kit.maillist.ListScope
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.reader.MessagePlaceResolver
import com.frederikberg.mailverdict.kit.reader.PlaceResolution
import kotlinx.coroutines.CancellationException

/** A message a search hit names, with where the hit found it. */
data class MessageTarget(
    val messageId: String,
    val accountId: String,
    val folderId: String,
)

/**
 * A message and the list it is opened in: [scope] under [title], and [folderIds] the folders that
 * list shows, which is what opening the conversation marks read.
 */
data class MessagePlace(
    val scope: ListScope,
    val title: String,
    val messageId: String,
    val folderIds: Set<String>,
)

/**
 * Where opening [messageId] leads now, through the one resolver every "open this message" goes
 * through. Null when the message is gone or the server cannot be asked.
 */
suspend fun MessagePlaceResolver.place(
    api: ApiClient,
    messageId: String,
): MessagePlace? {
    val found = resolve(messageId) as? PlaceResolution.Found ?: return null
    return when (val scope = found.scope) {
        is ListScope.Folder ->
            MessagePlace(scope, folderTitle(api, scope) ?: FALLBACK_TITLE, messageId, setOf(scope.folderId))
        is ListScope.Unified ->
            MessagePlace(scope, scope.name, messageId, unifiedFolderIds(api, scope.viewId))
    }
}

private suspend fun folderTitle(
    api: ApiClient,
    scope: ListScope.Folder,
): String? =
    lookup { api.listFolders(scope.accountId) }
        ?.firstOrNull { it.id == scope.folderId }
        ?.let { folderDisplayName(it.imapName, it.displayName, it.specialUse) }

private suspend fun unifiedFolderIds(
    api: ApiClient,
    viewId: String,
): Set<String> =
    lookup { api.listUnifiedFolders() }
        ?.firstOrNull { it.id == viewId }
        ?.folders
        ?.map { it.folderId }
        ?.toSet()
        .orEmpty()

private suspend fun <T> lookup(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: ApiError) {
        null
    }

private const val FALLBACK_TITLE = "Mail"
