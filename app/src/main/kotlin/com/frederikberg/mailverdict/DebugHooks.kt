package com.frederikberg.mailverdict

import android.app.Application
import timber.log.Timber

/**
 * Entry point into the debug source set. Release builds contain no debug code, so the installer is
 * looked up by name and a missing class is the normal outcome there.
 */
internal object DebugHooks {
    private const val INSTALLER = "com.frederikberg.mailverdict.debug.DebugInstaller"

    fun graphCreated(
        app: Application,
        graph: AppGraph,
    ) {
        val installer =
            try {
                Class.forName(INSTALLER)
            } catch (_: ClassNotFoundException) {
                return
            }
        try {
            installer
                .getMethod("install", Application::class.java, AppGraph::class.java)
                .invoke(null, app, graph)
        } catch (e: ReflectiveOperationException) {
            Timber.w(e, "debug installer failed")
        }
    }
}
