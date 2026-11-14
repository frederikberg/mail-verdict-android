package com.frederikberg.mailverdict.debug

import android.app.Application
import android.content.pm.ApplicationInfo
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.session.SessionState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

/** Starts the in-memory log and the debug server; called by name from the main source set. */
object DebugInstaller {
    const val PORT = 8765

    private var server: DebugServer? = null
    private val logs = RingBufferLogTree()
    private var planted = false

    @JvmStatic
    @Synchronized
    fun install(
        app: Application,
        graph: AppGraph,
    ) {
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (!planted) {
            Timber.plant(logs)
            planted = true
        }
        server?.stop()
        server =
            DebugServer({ stateOf(graph) }, logs, versionName(app), PORT).also {
                it.start()
                Timber.i("debug server on 127.0.0.1:%d", PORT)
            }
    }

    private fun stateOf(graph: AppGraph): JsonObject {
        val session = graph.session.state.value
        return JsonObject(
            mapOf(
                "fixtureMode" to JsonPrimitive(graph.fixtureMode),
                "signedIn" to JsonPrimitive(session is SessionState.SignedIn),
                "serverUrl" to JsonPrimitive((session as? SessionState.SignedIn)?.connection?.serverUrl),
            ),
        )
    }

    private fun versionName(app: Application): String =
        app.packageManager
            .getPackageInfo(
                app.packageName,
                0,
            ).versionName
            .orEmpty()
}
