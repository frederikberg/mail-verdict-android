package com.frederikberg.mailverdict

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch

/**
 * Where the browser leaves the sign-in: the provider redirects to `mailverdict://auth/callback` and
 * this activity hands the full address to the session, then brings the app forward. It draws nothing.
 * Whether the address belongs to the attempt in progress is the session's decision, never this
 * activity's: any app can fire this intent.
 */
class RedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as MailVerdictApp).currentGraph()
        intent?.data?.toString()?.let { callback ->
            graph.appScope.launch { graph.session.completeOidcSignIn(callback) }
        }
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }
}
