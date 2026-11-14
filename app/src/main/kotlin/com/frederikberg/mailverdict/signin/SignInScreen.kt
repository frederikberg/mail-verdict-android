package com.frederikberg.mailverdict.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.kit.session.Rejection
import com.frederikberg.mailverdict.kit.session.SignInStatus

/** Stable handles for tests and for driving the screen on a device. */
object SignInTags {
    const val SERVER = "signin-server"
    const val SIGN_IN = "signin-oidc"
    const val ADVANCED = "signin-advanced"
    const val TOKEN = "signin-token"
    const val TOKEN_SIGN_IN = "signin-token-button"
    const val STATUS = "signin-status"
}

/** What the sign-in screen draws; the route that owns the typing and the browser holds the rest. */
data class SignInUiState(
    val serverUrl: String,
    val token: String,
    val advancedShown: Boolean,
    val status: SignInStatus,
    /** Set when a stored credential was refused, so the screen can say that instead of reading as a first launch. */
    val rejection: Rejection? = null,
    val fixtureMode: Boolean = false,
) {
    val busy: Boolean get() = status is SignInStatus.Working || status is SignInStatus.Exchanging
    val waitingForBrowser: Boolean get() = status is SignInStatus.WaitingForBrowser
    val cleartext: Boolean get() = serverUrl.trim().lowercase().startsWith("http://")
}

@Composable
fun SignInScreen(
    state: SignInUiState,
    onServerUrl: (String) -> Unit,
    onToken: (String) -> Unit,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onToggleAdvanced: () -> Unit,
    onPasteToken: () -> Unit,
    onSignInWithToken: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.signin_title), style = MaterialTheme.typography.titleLarge)

            if (state.rejection != null) {
                Column {
                    Text(
                        stringResource(R.string.signin_rejected),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    state.rejection.detail?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onServerUrl,
                label = { Text(stringResource(R.string.signin_server_label)) },
                placeholder = { Text(stringResource(R.string.signin_server_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !state.busy && !state.waitingForBrowser,
                modifier = Modifier.fillMaxWidth().testTag(SignInTags.SERVER),
            )
            if (state.cleartext) {
                Text(
                    stringResource(R.string.signin_cleartext_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = onSignIn,
                enabled = state.serverUrl.isNotBlank() && !state.busy && !state.waitingForBrowser,
                modifier = Modifier.fillMaxWidth().testTag(SignInTags.SIGN_IN),
            ) {
                Text(stringResource(R.string.signin_button))
            }
            Status(state, onCancel)
            Text(stringResource(R.string.signin_footer), style = MaterialTheme.typography.bodySmall)

            TextButton(onClick = onToggleAdvanced, modifier = Modifier.testTag(SignInTags.ADVANCED)) {
                Text(
                    stringResource(
                        if (state.advancedShown) R.string.signin_advanced_hide else R.string.signin_advanced_show,
                    ),
                )
            }
            if (state.advancedShown) {
                OutlinedTextField(
                    value = state.token,
                    onValueChange = onToken,
                    label = { Text(stringResource(R.string.signin_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag(SignInTags.TOKEN),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onPasteToken, enabled = !state.busy) {
                        Text(stringResource(R.string.signin_token_paste))
                    }
                    Button(
                        onClick = onSignInWithToken,
                        enabled = state.serverUrl.isNotBlank() && state.token.isNotBlank() && !state.busy,
                        modifier = Modifier.testTag(SignInTags.TOKEN_SIGN_IN),
                    ) {
                        Text(stringResource(R.string.signin_token_button))
                    }
                }
                Text(stringResource(R.string.signin_token_note), style = MaterialTheme.typography.bodySmall)
            }
            if (state.fixtureMode) {
                Text(stringResource(R.string.fixture_mode_label), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Status(
    state: SignInUiState,
    onCancel: () -> Unit,
) {
    when (val status = state.status) {
        SignInStatus.Idle -> Unit
        SignInStatus.Working -> Progress(R.string.signin_working, onCancel = null)
        SignInStatus.Exchanging -> Progress(R.string.signin_exchanging, onCancel = null)
        SignInStatus.WaitingForBrowser -> Progress(R.string.signin_waiting, onCancel)
        is SignInStatus.Failed ->
            Text(
                status.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(SignInTags.STATUS),
            )
    }
}

@Composable
private fun Progress(
    label: Int,
    onCancel: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag(SignInTags.STATUS),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.signin_cancel)) }
    }
}
