package com.frederikberg.mailverdict.push

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.contract.PushChannel
import com.frederikberg.mailverdict.kit.push.PushStatus

object PushTags {
    const val STATUS = "push-status"
    const val ENABLE = "push-enable"
    const val DISABLE = "push-disable"
    const val GET_DISTRIBUTOR = "push-get-distributor"
    const val CHOOSE_DISTRIBUTOR = "push-choose-distributor"
    const val ALLOW_SYSTEM = "push-allow-system"
    const val TEST = "push-test"

    fun channel(channel: PushChannel) = "push-channel:${channel.raw}"
}

class PushSettingsCallbacks(
    val onBack: () -> Unit,
    val onEnable: () -> Unit,
    val onDisable: () -> Unit,
    val onSendTest: () -> Unit,
    val onGetDistributor: () -> Unit,
    val onChooseDistributor: () -> Unit,
    val onAllowSystem: () -> Unit,
    val onMuted: (PushChannel, Boolean) -> Unit,
)

/**
 * The screen that explains the helper app and turns notifications on and off. What it offers is decided
 * by [status] alone, so the first thing someone without a helper app sees is the one step they need.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushSettingsScreen(
    status: PushStatus,
    systemAllowed: Boolean,
    notice: String?,
    callbacks: PushSettingsCallbacks,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.push_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.push_intro), style = MaterialTheme.typography.bodyMedium)
            Text(
                statusText(status),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(PushTags.STATUS),
            )
            when (status) {
                PushStatus.NoDistributor -> {
                    Text(
                        stringResource(R.string.push_get_distributor_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = callbacks.onGetDistributor,
                        modifier = Modifier.testTag(PushTags.GET_DISTRIBUTOR),
                    ) {
                        Text(stringResource(R.string.push_get_distributor))
                    }
                    OutlinedButton(onClick = callbacks.onEnable, modifier = Modifier.testTag(PushTags.ENABLE)) {
                        Text(stringResource(R.string.push_enable))
                    }
                }
                PushStatus.Off, is PushStatus.Failed, is PushStatus.LoadFailed ->
                    Button(onClick = callbacks.onEnable, modifier = Modifier.testTag(PushTags.ENABLE)) {
                        Text(stringResource(R.string.push_enable))
                    }
                is PushStatus.Registered -> Registered(status, callbacks)
                PushStatus.Loading, PushStatus.Registering, PushStatus.ServerUnavailable -> Unit
            }
            if (!systemAllowed && status is PushStatus.Registered) {
                Text(stringResource(R.string.push_allow_system_hint), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = callbacks.onAllowSystem, modifier = Modifier.testTag(PushTags.ALLOW_SYSTEM)) {
                    Text(stringResource(R.string.push_allow_system))
                }
            }
            notice?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Registered(
    status: PushStatus.Registered,
    callbacks: PushSettingsCallbacks,
) {
    status.subscription.label?.let {
        Text(stringResource(R.string.push_device_label, it), style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
    Text(stringResource(R.string.push_channels_title), style = MaterialTheme.typography.titleSmall)
    ChannelRow(
        stringResource(R.string.push_channel_mail),
        stringResource(R.string.push_channel_mail_summary),
        PushChannel.Mail,
        status,
        callbacks,
    )
    ChannelRow(
        stringResource(R.string.push_channel_system),
        stringResource(R.string.push_channel_system_summary),
        PushChannel.System,
        status,
        callbacks,
    )
    HorizontalDivider()
    OutlinedButton(onClick = callbacks.onSendTest, modifier = Modifier.testTag(PushTags.TEST)) {
        Text(stringResource(R.string.push_send_test))
    }
    OutlinedButton(onClick = callbacks.onChooseDistributor, modifier = Modifier.testTag(PushTags.CHOOSE_DISTRIBUTOR)) {
        Text(stringResource(R.string.push_choose_distributor))
    }
    OutlinedButton(onClick = callbacks.onDisable, modifier = Modifier.testTag(PushTags.DISABLE)) {
        Text(stringResource(R.string.push_disable))
    }
}

@Composable
private fun ChannelRow(
    title: String,
    summary: String,
    channel: PushChannel,
    status: PushStatus.Registered,
    callbacks: PushSettingsCallbacks,
) {
    val on = channel !in status.subscription.mutedChannels
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = {
            callbacks.onMuted(channel, !it)
        }, modifier = Modifier.testTag(PushTags.channel(channel)))
    }
}

@Composable
private fun statusText(status: PushStatus): String =
    when (status) {
        PushStatus.Loading -> stringResource(R.string.push_status_loading)
        is PushStatus.LoadFailed -> stringResource(R.string.push_status_load_failed, status.message)
        PushStatus.ServerUnavailable -> stringResource(R.string.push_status_server_unavailable)
        PushStatus.NoDistributor -> stringResource(R.string.push_status_no_distributor)
        PushStatus.Off -> stringResource(R.string.push_status_off)
        PushStatus.Registering -> stringResource(R.string.push_status_registering)
        is PushStatus.Registered -> stringResource(R.string.push_status_registered)
        is PushStatus.Failed -> stringResource(R.string.push_status_failed, status.message)
    }
