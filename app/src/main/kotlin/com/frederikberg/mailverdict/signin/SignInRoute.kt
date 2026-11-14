package com.frederikberg.mailverdict.signin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.frederikberg.mailverdict.kit.session.Rejection
import com.frederikberg.mailverdict.kit.session.SessionController

/**
 * The sign-in screen wired to the session: keeps the address the person has typed across rotation and
 * process death, and turns taps into session calls. [openBrowser]
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
    val status by session.signInStatus.collectAsState()

    SignInScreen(
        state = SignInUiState(serverUrl, status, rejection, fixtureMode),
        onServerUrl = { serverUrl = it },
        onSignIn = {
            launch { session.beginOidcSignIn(serverUrl)?.let(openBrowser) }
        },
        onCancel = session::cancelSignIn,
    )
}
