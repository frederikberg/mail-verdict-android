package com.frederikberg.mailverdict.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R

/** The empty app: a title bar over a line saying there is nothing to show yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(fixtureMode: Boolean) {
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.shell_no_account), style = MaterialTheme.typography.titleMedium)
            if (fixtureMode) {
                Text(stringResource(R.string.shell_fixture_mode), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
