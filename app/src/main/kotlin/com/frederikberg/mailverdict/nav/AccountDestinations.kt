package com.frederikberg.mailverdict.nav

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.accounts.AccountDetailRoute
import com.frederikberg.mailverdict.accounts.AccountsRoute
import com.frederikberg.mailverdict.accounts.AddAccountRoute
import com.frederikberg.mailverdict.accounts.EditAccountRoute
import com.frederikberg.mailverdict.accounts.accountsStore
import com.frederikberg.mailverdict.accounts.identitiesStore
import com.frederikberg.mailverdict.kit.session.Connection
import com.frederikberg.mailverdict.kit.signins.SignInsStore
import com.frederikberg.mailverdict.rules.RulesRoute
import com.frederikberg.mailverdict.rules.rulesStore
import com.frederikberg.mailverdict.settings.UserSettingsRoute
import com.frederikberg.mailverdict.settings.userSettingsStore
import com.frederikberg.mailverdict.signins.SignInsRoute

/**
 * The screens for accounts, rules, mail settings and signed-in devices. Signing out the current device
 * from the sign-ins list is [onSignOut], the same sign-out the settings screen uses.
 */
fun NavGraphBuilder.accountDestinations(
    graph: AppGraph,
    connection: Connection,
    navController: NavHostController,
    onSignOut: () -> Unit,
) {
    val back: () -> Unit = { navController.popBackStack() }
    composable<Route.Accounts> {
        AccountsRoute(
            store = graph.accountsStore(connection),
            onBack = back,
            onAdd = { navController.navigate(Route.AddAccount) },
            onOpen = { navController.navigate(Route.Account(it)) },
        )
    }
    composable<Route.AddAccount> { AddAccountRoute(store = graph.accountsStore(connection), onBack = back) }
    composable<Route.Account> { entry ->
        val accountId = entry.toRoute<Route.Account>().accountId
        AccountDetailRoute(
            store = graph.accountsStore(connection),
            identities = graph.identitiesStore(connection, accountId),
            accountId = accountId,
            onBack = back,
            onEdit = { navController.navigate(Route.EditAccount(accountId)) },
        )
    }
    composable<Route.EditAccount> { entry ->
        EditAccountRoute(
            store = graph.accountsStore(connection),
            accountId = entry.toRoute<Route.EditAccount>().accountId,
            onBack = back,
        )
    }
    composable<Route.Rules> {
        RulesRoute(store = graph.rulesStore(connection), accounts = graph.accountsStore(connection), onBack = back)
    }
    composable<Route.MailSettings> { UserSettingsRoute(store = graph.userSettingsStore(connection), onBack = back) }
    composable<Route.SignIns> {
        val store = remember(connection) { SignInsStore(connection.api, onSignOut) }
        SignInsRoute(store = store, onBack = back)
    }
}
