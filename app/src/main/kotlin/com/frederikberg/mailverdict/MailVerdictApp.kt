package com.frederikberg.mailverdict

import android.app.Application

class MailVerdictApp : Application() {
    private var current: AppGraph? = null

    /**
     * The graph for the requested mode, kept until a request for the other mode replaces it. The
     * launch flag is only known once an activity has an intent, so the graph is built on demand
     * rather than in [onCreate].
     */
    @Synchronized
    fun graphFor(fixtureMode: Boolean): AppGraph {
        current?.takeIf { it.fixtureMode == fixtureMode }?.let { return it }
        return AppGraph.create(fixtureMode).also {
            current = it
            DebugHooks.graphCreated(this, it)
        }
    }
}
