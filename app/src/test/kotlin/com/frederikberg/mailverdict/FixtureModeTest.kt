package com.frederikberg.mailverdict

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FixtureModeTest {
    private val withFlag = Intent().putExtra(FixtureMode.EXTRA, true)

    @Test
    fun theFlagStartsFixtureModeInADebugBuild() {
        assertTrue(FixtureMode.requested(withFlag, debuggable = true))
    }

    @Test
    fun theFlagIsIgnoredInAReleaseBuild() {
        assertFalse(FixtureMode.requested(withFlag, debuggable = false))
    }

    @Test
    fun noFlagAndNoIntentAreLiveMode() {
        assertFalse(FixtureMode.requested(Intent(), debuggable = true))
        assertFalse(FixtureMode.requested(null, debuggable = true))
    }
}
