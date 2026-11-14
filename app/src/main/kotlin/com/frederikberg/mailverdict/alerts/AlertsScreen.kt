package com.frederikberg.mailverdict.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.contract.AlertResponse
import com.frederikberg.mailverdict.kit.alerts.AlertsState
import com.frederikberg.mailverdict.kit.search.SearchRowFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

object AlertsTags {
    const val LIST = "alerts-list"
    const val TAB_MAIL = "alerts-tab-mail"
    const val TAB_SYSTEM = "alerts-tab-system"
    const val DISMISS_ALL = "alerts-dismiss-all"
    const val PUSH_BANNER = "alerts-push-banner"
    const val SETTINGS = "alerts-settings"
    const val BELL = "alerts-bell"
    const val EMPTY = "alerts-empty"

    fun row(id: String) = "alert:$id"

    fun dismiss(id: String) = "alert-dismiss:$id"
}

class AlertsCallbacks(
    val onBack: () -> Unit,
    val onOpen: (AlertResponse) -> Unit,
    val onDismiss: (AlertResponse) -> Unit,
    val onDismissAll: (system: Boolean) -> Unit,
    val onOpenPushSettings: () -> Unit,
)

/** The bell on the overview: a count from the server, nothing when it is zero. */
@Composable
fun AlertBell(
    count: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(AlertsTags.BELL)) {
        BadgedBox(
            badge = { if (count > 0) Badge { Text(if (count > BADGE_CAP) "$BADGE_CAP+" else count.toString()) } },
        ) {
            Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.alerts_title))
        }
    }
}

private const val BADGE_CAP = 99

/**
 * The alert list in its two halves, new mail and everything else, each dismissable one by one or all
 * at once. [showPushBanner] offers turning on notifications while they are off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    state: AlertsState,
    showPushBanner: Boolean,
    callbacks: AlertsCallbacks,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alerts_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val list = if (tab == 0) state.mailAlerts else state.systemAlerts
                    if (list.isNotEmpty()) {
                        TextButton(
                            onClick = { callbacks.onDismissAll(tab == 1) },
                            modifier = Modifier.testTag(AlertsTags.DISMISS_ALL),
                        ) {
                            Text(stringResource(R.string.alerts_dismiss_all))
                        }
                    }
                    IconButton(
                        onClick = callbacks.onOpenPushSettings,
                        modifier = Modifier.testTag(AlertsTags.SETTINGS),
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.push_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showPushBanner) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().testTag(AlertsTags.PUSH_BANNER),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.alerts_push_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = callbacks.onOpenPushSettings,
                        ) { Text(stringResource(R.string.alerts_push_banner_action)) }
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.alerts_tab_mail, state.mailAlerts.size)) },
                    modifier = Modifier.testTag(AlertsTags.TAB_MAIL),
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.alerts_tab_system, state.systemAlerts.size)) },
                    modifier = Modifier.testTag(AlertsTags.TAB_SYSTEM),
                )
            }
            state.errorMessage?.let {
                Text(
                    stringResource(R.string.alerts_error, it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            val list = if (tab == 0) state.mailAlerts else state.systemAlerts
            when {
                state.isLoading ->
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                list.isEmpty() ->
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                        Text(
                            stringResource(if (tab == 0) R.string.alerts_empty_mail else R.string.alerts_empty_system),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag(AlertsTags.EMPTY),
                        )
                    }
                else ->
                    LazyColumn(Modifier.fillMaxSize().testTag(AlertsTags.LIST)) {
                        items(list, key = { it.id }) { alert ->
                            AlertRow(
                                alert,
                                onClick = { callbacks.onOpen(alert) },
                                onDismiss = { callbacks.onDismiss(alert) },
                            )
                            HorizontalDivider()
                        }
                    }
            }
        }
    }
}

@Composable
private fun AlertRow(
    alert: AlertResponse,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val date =
        remember(alert.createdAt) {
            SearchRowFormat.dateLabel(alert.createdAt, Instant.now(), ZoneId.systemDefault(), Locale.getDefault())
        }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
            ).padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
            .testTag(AlertsTags.row(alert.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    alert.title ?: stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    date,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            alert.body?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag(AlertsTags.dismiss(alert.id))) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.alerts_dismiss))
        }
    }
}
