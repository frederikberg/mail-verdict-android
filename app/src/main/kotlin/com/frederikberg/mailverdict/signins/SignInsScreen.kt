package com.frederikberg.mailverdict.signins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.contract.SignInKind
import com.frederikberg.mailverdict.contract.SignInResponse
import com.frederikberg.mailverdict.kit.format.MailFormat
import com.frederikberg.mailverdict.kit.signins.SignInDisplay
import com.frederikberg.mailverdict.kit.signins.SignInsState
import com.frederikberg.mailverdict.kit.signins.isRevoked

object SignInsTags {
    const val LIST = "signins-list"
    const val EMPTY = "signins-empty"
    const val ERROR = "signins-error"
    const val CONFIRM = "signins-confirm"

    fun row(id: String) = "signin:$id"

    fun current(id: String) = "signin-current:$id"

    fun signOut(id: String) = "signin-sign-out:$id"
}

class SignInsCallbacks(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onRevoke: (SignInResponse) -> Unit,
)

/**
 * The person's own sign-ins: this phone, others, the web browsers together, machine clients, with the
 * current one marked. Signing one out asks first and says what it does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInsScreen(
    state: SignInsState,
    snackbar: SnackbarHostState,
    callbacks: SignInsCallbacks,
) {
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val confirming = state.signIns.firstOrNull { it.id == target }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.signins_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.errorMessage?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 16.dp,
                                vertical = 8.dp,
                            ).testTag(SignInsTags.ERROR),
                ) {
                    Text(
                        stringResource(R.string.signins_error, it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = callbacks.onRetry) { Text(stringResource(R.string.signins_retry)) }
                }
            }
            Text(
                stringResource(R.string.signins_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            when {
                state.isLoading && state.signIns.isEmpty() ->
                    CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
                state.hasLoaded && state.signIns.isEmpty() ->
                    Text(
                        stringResource(R.string.signins_empty),
                        modifier = Modifier.padding(16.dp).testTag(SignInsTags.EMPTY),
                    )
                else ->
                    LazyColumn(Modifier.fillMaxSize().testTag(SignInsTags.LIST)) {
                        items(state.signIns, key = { it.id }) { signIn ->
                            SignInLine(signIn, signIn.id in state.revoking) { target = signIn.id }
                            HorizontalDivider()
                        }
                    }
            }
        }
    }
    if (confirming != null) {
        val label = SignInDisplay.label(confirming)
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(stringResource(R.string.signins_confirm_title, label)) },
            text = { Text(SignInDisplay.consequence(confirming)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        target = null
                        callbacks.onRevoke(confirming)
                    },
                    modifier = Modifier.testTag(SignInsTags.CONFIRM),
                ) { Text(stringResource(R.string.signins_sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SignInLine(
    signIn: SignInResponse,
    revoking: Boolean,
    onSignOut: () -> Unit,
) {
    val label = SignInDisplay.label(signIn)
    val revoked = signIn.isRevoked
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 16.dp,
                    vertical = 12.dp,
                ).testTag(SignInsTags.row(signIn.id)),
    ) {
        Text(glyph(signIn.kind), style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (revoked) TextDecoration.LineThrough else null,
                    color =
                        if (revoked) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (signIn.current) {
                    Chip(
                        stringResource(R.string.signins_this_one),
                        false,
                        Modifier.testTag(SignInsTags.current(signIn.id)),
                    )
                }
                if (revoked) Chip(stringResource(R.string.signins_signed_out), true)
            }
            Text(
                if (revoked) {
                    stringResource(R.string.signins_signed_out_at, MailFormat.fullDate(signIn.revokedAt))
                } else {
                    stringResource(R.string.signins_last_used, MailFormat.fullDate(signIn.lastSeenAt))
                } + " · " + stringResource(R.string.signins_first_seen, MailFormat.listDate(signIn.firstSeenAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!revoked) {
            OutlinedButton(
                onClick = onSignOut,
                enabled = !revoking,
                modifier = Modifier.testTag(SignInsTags.signOut(signIn.id)),
            ) { Text(stringResource(R.string.signins_sign_out)) }
        }
    }
}

@Composable
private fun Chip(
    text: String,
    error: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (error) colors.errorContainer else colors.secondaryContainer,
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (error) colors.onErrorContainer else colors.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

private fun glyph(kind: SignInKind): String =
    when (kind) {
        SignInKind.Phone -> "📱"
        SignInKind.Browser -> "🌐"
        else -> "🔑"
    }
