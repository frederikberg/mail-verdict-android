package com.frederikberg.mailverdict.platform

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun prefs(fixtureMode: Boolean = false) = AppPreferences(context, fixtureMode)

    @Test
    fun theThemeFollowsTheSystemUntilChosenAndThenSurvivesARestart() {
        assertEquals(ThemeChoice.System, prefs().theme.value)

        prefs().setTheme(ThemeChoice.Dark)

        assertEquals(ThemeChoice.Dark, prefs().theme.value)
    }

    @Test
    fun theServerAddressSurvivesARestart() {
        assertNull(prefs().read())

        prefs().write("https://mail.example.com")

        assertEquals("https://mail.example.com", prefs().read())
    }

    @Test
    fun collapsedSectionsSurviveARestartAndExpandingForgetsThem() {
        prefs().setCollapsed("unified", true)
        prefs().setCollapsed("account:1", true)
        assertEquals(setOf("unified", "account:1"), prefs().collapsedKeys())

        prefs().setCollapsed("unified", false)

        assertEquals(setOf("account:1"), prefs().collapsedKeys())
    }

    @Test
    fun aFixtureRunNeverTouchesTheRealSettings() {
        prefs(fixtureMode = true).setTheme(ThemeChoice.Light)
        prefs(fixtureMode = true).write("https://fixture.invalid")
        prefs(fixtureMode = true).setCollapsed("unified", true)

        assertEquals(ThemeChoice.System, prefs().theme.value)
        assertNull(prefs().read())
        assertFalse("unified" in prefs().collapsedKeys())
        assertTrue("unified" in prefs(fixtureMode = true).collapsedKeys())
    }
}
