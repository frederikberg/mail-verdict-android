package com.frederikberg.mailverdict.alerts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.alerts.AlertsStore
import com.frederikberg.mailverdict.kit.push.PushStatus
import com.frederikberg.mailverdict.push.PushNotifier
import kotlinx.coroutines.launch

@Composable
fun AlertsRoute(
    graph: AppGraph,
    store: AlertsStore,
    onOpenMessage: (messageId: String) -> Unit,
    onOpenPushSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val notifier = remember { PushNotifier(context) }
    val scope = rememberCoroutineScope()
    val state by store.state.collectAsState()
    val pushStatus by graph.push.status.collectAsState()

    LaunchedEffect(store) {
        store.load()
        graph.push.refresh()
    }

    AlertsScreen(
        state = state,
        showPushBanner =
            pushStatus.let {
                it !is PushStatus.Registered &&
                    it !is PushStatus.Loading &&
                    it !is PushStatus.ServerUnavailable
            },
        callbacks =
            AlertsCallbacks(
                onBack = onBack,
                onOpen = { alert ->
                    scope.launch {
                        val messageId = store.open(alert)
                        notifier.cancel(alert.id)
                        if (messageId != null) onOpenMessage(messageId)
                    }
                },
                onDismiss = { alert ->
                    scope.launch {
                        store.dismiss(alert.id)
                        notifier.cancel(alert.id)
                    }
                },
                onDismissAll = { system ->
                    scope.launch { if (system) store.dismissAllSystem() else store.dismissAllMail() }
                },
                onOpenPushSettings = onOpenPushSettings,
            ),
    )
}
