package com.frederikberg.mailverdict

import android.content.Intent

/**
 * Launch flag that starts the app on canned data: `adb shell am start -n
 * com.frederikberg.mailverdict/.MainActivity --ez fixture_mode true`. Honoured in debug builds
 * only, so no other app can swap a release build's server for canned data.
 */
object FixtureMode {
    const val EXTRA = "fixture_mode"

    fun requested(
        intent: Intent?,
        debuggable: Boolean = BuildConfig.DEBUG,
    ): Boolean = debuggable && intent?.getBooleanExtra(EXTRA, false) == true
}
