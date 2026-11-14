package com.frederikberg.mailverdict.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.session.Connection
import com.frederikberg.mailverdict.mailboxes.MailboxesRoute
import com.frederikberg.mailverdict.maillist.MailListRoute
import com.frederikberg.mailverdict.maillist.toListScope
import com.frederikberg.mailverdict.reader.ReaderRoute
import com.frederikberg.mailverdict.settings.SettingsScreen
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
}

@Composable
fun AppNavigation(
    graph: AppGraph,
    connection: Connection,
    versionName: String,
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()
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
