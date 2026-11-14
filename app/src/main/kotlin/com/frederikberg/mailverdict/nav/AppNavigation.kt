package com.frederikberg.mailverdict.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.kit.session.Connection
import com.frederikberg.mailverdict.mailboxes.MailboxesRoute
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
                onOpenUnified = { navController.navigate(Route.MailList(it.name, unifiedViewId = it.id)) },
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
            MailListPlaceholder(entry.toRoute<Route.MailList>().title) { navController.popBackStack() }
        }
    }
}

/** Stands where the message list goes; a folder or unified view opens here until that screen exists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailListPlaceholder(
    title: String,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.maillist_placeholder))
        }
    }
}
