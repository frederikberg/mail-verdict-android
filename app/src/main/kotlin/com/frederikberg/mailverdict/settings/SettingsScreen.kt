package com.frederikberg.mailverdict.settings

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.contract.MeResponse
import com.frederikberg.mailverdict.platform.ThemeChoice

object SettingsTags {
    const val SIGN_OUT = "settings-sign-out"
    const val CONFIRM_SIGN_OUT = "settings-sign-out-confirm"
}

/** Theme, who is signed in to which server, sign-out, and the app's version. Each choice applies at once; nothing here has a Save button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    theme: ThemeChoice,
    serverUrl: String,
    me: MeResponse?,
    versionName: String,
    onTheme: (ThemeChoice) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section(R.string.settings_appearance) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val choices =
                        listOf(
                            ThemeChoice.System to R.string.settings_theme_system,
                            ThemeChoice.Light to R.string.settings_theme_light,
                            ThemeChoice.Dark to R.string.settings_theme_dark,
                        )
                    choices.forEachIndexed { index, (choice, label) ->
                        SegmentedButton(
                            selected = theme == choice,
                            onClick = { onTheme(choice) },
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            Section(R.string.settings_connection) {
                Line(R.string.settings_server, serverUrl)
                me?.let {
                    Line(R.string.settings_signed_in_as, it.displayName)
                    it.email?.let { email -> Line(R.string.settings_email, email) }
                    Line(
                        R.string.settings_role,
                        stringResource(if (it.isAdmin) R.string.settings_role_admin else R.string.settings_role_user),
                    )
                }
                OutlinedButton(onClick = { confirming = true }, modifier = Modifier.testTag(SettingsTags.SIGN_OUT)) {
                    Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error)
                }
            }
            Section(R.string.settings_about) {
                Line(R.string.settings_version, versionName)
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onSignOut()
                    },
                    modifier = Modifier.testTag(SettingsTags.CONFIRM_SIGN_OUT),
                ) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.signin_cancel)) }
            },
        )
    }
}

@Composable
private fun Section(
    title: Int,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun Line(
    label: Int,
    value: String,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
