package com.frederikberg.mailverdict.accounts

import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.accounts.AccountsStore
import com.frederikberg.mailverdict.kit.accounts.IdentitiesStore
import com.frederikberg.mailverdict.kit.session.Connection

/** The accounts of [connection], shared by every screen that lists or changes them for as long as it lasts. */
fun AppGraph.accountsStore(connection: Connection): AccountsStore =
    connectionStores.get(connection, AccountsStore::class) { scope ->
        AccountsStore(connection.api, scope).also { it.follow(connection.liveEventHub.events) }
    }

/** One account's sending identities, kept in step with the server's `identity.changed` events. */
fun AppGraph.identitiesStore(
    connection: Connection,
    accountId: String,
): IdentitiesStore =
    connectionStores.get(connection, "identities:$accountId") { scope ->
        IdentitiesStore(connection.api, scope, accountId).also { it.follow(connection.liveEventHub.events) }
    }
