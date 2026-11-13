package com.frederikberg.mailverdict

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.frederikberg.mailverdict.ui.ShellScreen
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as MailVerdictApp).graphFor(FixtureMode.requested(intent))
        setContent {
            MailVerdictTheme {
                ShellScreen(fixtureMode = graph.fixtureMode)
            }
        }
    }
}
