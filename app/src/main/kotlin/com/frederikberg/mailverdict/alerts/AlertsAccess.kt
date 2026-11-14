package com.frederikberg.mailverdict.alerts

import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.alerts.AlertsStore
import com.frederikberg.mailverdict.kit.session.Connection

/** The alert list and the bell's count of [connection], kept for as long as it lasts. */
fun AppGraph.alertsStore(connection: Connection): AlertsStore =
    connectionStores.get(connection, AlertsStore::class) { scope ->
        AlertsStore(connection.api, scope) { push.subscriptionId }.also { it.follow(connection.liveEventHub.events) }
    }
