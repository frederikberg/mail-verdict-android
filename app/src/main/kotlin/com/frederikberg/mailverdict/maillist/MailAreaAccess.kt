package com.frederikberg.mailverdict.maillist

import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.cache.FileOfflineCache
import com.frederikberg.mailverdict.kit.cache.NoOfflineCache
import com.frederikberg.mailverdict.kit.cache.OfflineCache
import com.frederikberg.mailverdict.kit.maillist.ListScope
import com.frederikberg.mailverdict.kit.maillist.MailArea
import com.frederikberg.mailverdict.kit.session.Connection
import com.frederikberg.mailverdict.nav.Route
import java.io.File
import java.security.MessageDigest

/** The list and reader stores of [connection], kept for as long as it lasts. */
fun AppGraph.mailArea(connection: Connection): MailArea =
    connectionStores.get(connection, MailArea::class) { scope ->
        MailArea(connection, scope, offlineCache(connection), mailPreferences, mailPreferences)
    }

/** One directory per server, so two servers never share a saved page; none at all in a fixture run, which keeps nothing. */
private fun AppGraph.offlineCache(connection: Connection): OfflineCache =
    if (fixtureMode) {
        NoOfflineCache
    } else {
        FileOfflineCache(File(mailCacheRoot, serverDirectory(connection.serverUrl)))
    }

private fun serverDirectory(serverUrl: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(serverUrl.trim().lowercase().toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }

/** The list a navigation route names. */
fun Route.MailList.toListScope(): ListScope =
    when {
        unifiedViewId != null -> ListScope.Unified(unifiedViewId, unifiedName ?: title)
        accountId != null && folderId != null -> ListScope.Folder(accountId, folderId)
        else -> error("a mail list route names a folder or a unified view")
    }
