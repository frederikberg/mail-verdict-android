package com.frederikberg.mailverdict.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.alerts.AlertsRoute
import com.frederikberg.mailverdict.alerts.alertsStore
import com.frederikberg.mailverdict.kit.maillist.ListScope
import com.frederikberg.mailverdict.kit.push.PushDestination
import com.frederikberg.mailverdict.kit.push.resolve
import com.frederikberg.mailverdict.kit.search.MessagePlace
import com.frederikberg.mailverdict.kit.search.MessageTarget
import com.frederikberg.mailverdict.kit.search.place
import com.frederikberg.mailverdict.kit.session.Connection
import com.frederikberg.mailverdict.mailboxes.MailboxesRoute
import com.frederikberg.mailverdict.maillist.MailListRoute
import com.frederikberg.mailverdict.maillist.mailArea
import com.frederikberg.mailverdict.maillist.toListScope
import com.frederikberg.mailverdict.push.PushSettingsRoute
import com.frederikberg.mailverdict.reader.ReaderRoute
import com.frederikberg.mailverdict.search.SearchRoute
import com.frederikberg.mailverdict.settings.SettingsScreen
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Every destination of the signed-in app. Serializable so the back stack survives process death. */
sealed interface Route {
    @Serializable
    data object Mailboxes : Route

    @Serializable
    data object Settings : Route

    /** One folder's messages or one unified view's; [title] is what the top bar shows. */
    @Serializable
    data class MailList(
        val title: String,
        val accountId: String? = null,
        val folderId: String? = null,
        val unifiedViewId: String? = null,
        val unifiedName: String? = null,
        /** Opens the window around this message instead of at the newest edge. */
        val aroundMessageId: String? = null,
    ) : Route

    /** One conversation, opened on the message [messageId]; [folderIds] are the folders its list shows. */
    @Serializable
    data class Reader(
        val messageId: String,
        val folderIds: List<String> = emptyList(),
    ) : Route

    /** Search, optionally narrowed to where it was opened from; [scopeLabel] names that place. */
    @Serializable
    data class Search(
        val accountId: String? = null,
        val folderId: String? = null,
        val scopeLabel: String? = null,
    ) : Route

    @Serializable
    data object Alerts : Route

    @Serializable
    data object PushSettings : Route
}

@Composable
fun AppNavigation(
    graph: AppGraph,
    connection: Connection,
    versionName: String,
    onSignOut: () -> Unit,
) {
    // Created with the connection, so what the last run left unsent is delivered without waiting for a screen to ask.
    remember(connection) { graph.mailArea(connection) }
    val navController = rememberNavController()
    val alerts = remember(connection) { graph.alertsStore(connection) }
    val alertBadge by alerts.badge.collectAsState()
    LaunchedEffect(alerts) { alerts.refreshBadge() }

    // A message named by search is opened as it is; one named only by its id (an alert, a notification)
    // is first placed in the list it now belongs to, so Back lands there.
    val openMessage = { target: MessageTarget ->
        navController.navigate(Route.Reader(target.messageId, listOf(target.folderId)))
    }
    val openPlace = { place: MessagePlace ->
        navController.navigate(place.listRoute())
        navController.navigate(Route.Reader(place.messageId, place.folderIds.toList()))
    }
    val scope = rememberCoroutineScope()
    val openMessageById = { messageId: String ->
        scope.launch {
            graph
                .mailArea(connection)
                .resolver
                .place(connection.api, messageId)
                ?.let(openPlace)
        }
        Unit
    }
    // Collected rather than keyed on the pending value: consuming it must not cancel the lookup it started.
    LaunchedEffect(graph, connection) {
        graph.deepLinks.pending.filterNotNull().collect { link ->
            graph.deepLinks.consume(link)
            when (val destination = link.resolve(graph.mailArea(connection).resolver, connection.api)) {
                is PushDestination.Message -> openPlace(destination.place)
                PushDestination.Alerts -> navController.navigate(Route.Alerts)
            }
        }
    }

    NavHost(navController, startDestination = Route.Mailboxes) {
        composable<Route.Mailboxes> {
            MailboxesRoute(
                graph = graph,
                connection = connection,
                onOpenFolder = {
                    navController.navigate(Route.MailList(it.displayName, it.accountId, folderId = it.id))
                },
                onOpenUnified = {
                    navController.navigate(Route.MailList(it.name, unifiedViewId = it.id, unifiedName = it.name))
                },
                onOpenSettings = { navController.navigate(Route.Settings) },
                alertBadge = alertBadge,
                onOpenSearch = { navController.navigate(Route.Search()) },
                onOpenAlerts = { navController.navigate(Route.Alerts) },
            )
        }
        composable<Route.Settings> {
            val theme by graph.preferences.theme.collectAsState()
            val me by connection.me.me.collectAsState()
            SettingsScreen(
                theme = theme,
                serverUrl = connection.serverUrl,
                me = me,
                versionName = versionName,
                onTheme = graph.preferences::setTheme,
                onSignOut = onSignOut,
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.Search> { entry ->
            val route = entry.toRoute<Route.Search>()
            SearchRoute(
                graph = graph,
                connection = connection,
                accountId = route.accountId,
                folderId = route.folderId,
                scopeLabel = route.scopeLabel,
                onOpenMessage = openMessage,
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.Alerts> {
            AlertsRoute(
                graph = graph,
                store = alerts,
                onOpenMessage = { openMessageById(it) },
                onOpenPushSettings = { navController.navigate(Route.PushSettings) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.PushSettings> {
            PushSettingsRoute(graph = graph, onBack = { navController.popBackStack() })
        }
        composable<Route.MailList> { entry ->
            val route = entry.toRoute<Route.MailList>()
            MailListRoute(
                graph = graph,
                connection = connection,
                scope = route.toListScope(),
                title = route.title,
                aroundMessageId = route.aroundMessageId,
                onBack = { navController.popBackStack() },
                onOpenMessage = { row, folders -> navController.navigate(Route.Reader(row.id, folders.toList())) },
            )
        }
        composable<Route.Reader> { entry ->
            val route = entry.toRoute<Route.Reader>()
            ReaderRoute(
                graph,
                connection,
                route.messageId,
                route.folderIds.toSet(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** The list route that shows [MessagePlace.messageId] in the place it belongs. */
private fun MessagePlace.listRoute(): Route.MailList =
    when (val scope = scope) {
        is ListScope.Folder -> Route.MailList(title, scope.accountId, scope.folderId, aroundMessageId = messageId)
        is ListScope.Unified ->
            Route.MailList(title, unifiedViewId = scope.viewId, unifiedName = scope.name, aroundMessageId = messageId)
    }
