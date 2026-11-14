package com.frederikberg.mailverdict.signin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboard
import com.frederikberg.mailverdict.kit.session.Rejection
import com.frederikberg.mailverdict.kit.session.SessionController
import kotlinx.coroutines.launch

/**
 * The sign-in screen wired to the session: keeps what the person has typed across rotation and
 * process death (the address, never the token), and turns taps into session calls. [openBrowser]
 * shows the provider's page and is the one thing here that needs an Activity.
 */
@Composable
fun SignInRoute(
    session: SessionController,
    rejection: Rejection?,
    fixtureMode: Boolean,
    openBrowser: (url: String) -> Unit,
    launch: (suspend () -> Unit) -> Unit,
) {
    var serverUrl by rememberSaveable { mutableStateOf(session.savedServerUrl) }
    var token by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val status by session.signInStatus.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    SignInScreen(
        state = SignInUiState(serverUrl, token, advanced, status, rejection, fixtureMode),
        onServerUrl = { serverUrl = it },
        onToken = { token = it },
        onSignIn = {
            launch { session.beginOidcSignIn(serverUrl)?.let(openBrowser) }
        },
        onCancel = session::cancelSignIn,
        onToggleAdvanced = { advanced = !advanced },
        onPasteToken = {
            scope.launch {
                clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let {
                    token = it.toString().trim()
                }
            }
        },
        onSignInWithToken = {
            launch { session.signInWithToken(serverUrl, token) }
        },
    )
}
