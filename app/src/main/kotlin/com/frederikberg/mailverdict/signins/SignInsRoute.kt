package com.frederikberg.mailverdict.signins

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import com.frederikberg.mailverdict.kit.signins.SignInsStore
import kotlinx.coroutines.launch

/** The sign-ins screen wired to its store. */
@Composable
fun SignInsRoute(
    store: SignInsStore,
    onBack: () -> Unit,
) {
    val state by store.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { store.load() }
    SignInsScreen(
        state = state,
        snackbar = snackbar,
        callbacks =
            SignInsCallbacks(
                onBack = onBack,
                onRetry = { scope.launch { store.load() } },
                onRevoke = { signIn ->
                    scope.launch {
                        val outcome = store.revoke(signIn)
                        if (outcome is WriteOutcome.Failed) snackbar.showSnackbar(outcome.message)
                    }
                },
            ),
    )
}
