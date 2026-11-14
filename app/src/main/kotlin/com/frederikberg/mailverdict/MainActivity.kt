package com.frederikberg.mailverdict

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.frederikberg.mailverdict.kit.session.SessionState
import com.frederikberg.mailverdict.nav.AppNavigation
import com.frederikberg.mailverdict.platform.ThemeChoice
import com.frederikberg.mailverdict.push.PushNotifier
import com.frederikberg.mailverdict.signin.SignInRoute
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph = (application as MailVerdictApp).graphFor(FixtureMode.requested(intent))
        graph.deepLinks.offer(PushNotifier.targetOf(intent))
        setContent {
            val theme by graph.preferences.theme.collectAsState()
            val sessionState by graph.session.state.collectAsState()
            MailVerdictTheme(
                darkTheme =
                    when (theme) {
                        ThemeChoice.System -> isSystemInDarkTheme()
                        ThemeChoice.Light -> false
                        ThemeChoice.Dark -> true
                    },
            ) {
                when (val state = sessionState) {
                    is SessionState.SignedOut ->
                        SignInRoute(
                            session = graph.session,
                            rejection = state.rejection,
                            fixtureMode = graph.fixtureMode,
                            openBrowser = ::openBrowser,
                            launch = { block -> graph.appScope.launch { block() } },
                        )
                    is SessionState.SignedIn ->
                        AppNavigation(
                            graph = graph,
                            connection = state.connection,
                            versionName = BuildConfig.VERSION_NAME,
                            onSignOut = graph.session::signOut,
                        )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        graph.deepLinks.offer(PushNotifier.targetOf(intent))
    }

    /** The stream runs only while the app is on screen; coming back reconnects at once and replays what was missed. */
    override fun onStart() {
        super.onStart()
        graph.session.onForeground()
    }

    override fun onStop() {
        graph.session.onBackground()
        super.onStop()
    }

    /** Called from the sign-in coroutine, which runs off the main thread. */
    private fun openBrowser(url: String) {
        runOnUiThread {
            try {
                CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
            } catch (_: ActivityNotFoundException) {
                graph.session.reportBrowserUnavailable()
            }
        }
    }
}
