package com.frederikberg.mailverdict.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import com.frederikberg.mailverdict.kit.session.Connection
import com.frederikberg.mailverdict.kit.settings.UserSettingsStore
import kotlinx.coroutines.launch

/** The person's own settings of [connection], kept for as long as it lasts. */
fun AppGraph.userSettingsStore(connection: Connection): UserSettingsStore =
    connectionStores.get(connection, UserSettingsStore::class) { scope ->
        UserSettingsStore(connection.api, scope).also { it.follow(connection.liveEventHub.events) }
    }

/** The settings screen wired to its store; a refused change is put back and said in a snackbar. */
@Composable
fun UserSettingsRoute(
    store: UserSettingsStore,
    onBack: () -> Unit,
) {
    val state by store.state.collectAsState()
    val sections = remember(state) { UserSettingsStore.sections(state) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { store.load() }
    UserSettingsScreen(
        state = state,
        sections = sections,
        snackbar = snackbar,
        callbacks =
            UserSettingsCallbacks(
                onBack = onBack,
                onRetry = { scope.launch { store.load() } },
                onSet = { field, value ->
                    scope.launch {
                        val outcome = store.set(field.spec.category, field.spec.key, value)
                        if (outcome is WriteOutcome.Failed) snackbar.showSnackbar(outcome.message)
                    }
                },
            ),
    )
}
