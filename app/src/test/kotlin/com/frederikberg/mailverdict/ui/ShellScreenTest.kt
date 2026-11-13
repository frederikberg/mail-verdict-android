package com.frederikberg.mailverdict.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ShellScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theEmptyShellSaysThereIsNothingYet() {
        compose.setContent { MailVerdictTheme(dynamicColor = false) { ShellScreen(fixtureMode = false) } }

        compose.onNodeWithText("MailVerdict").assertExists()
        compose.onNodeWithText("Not signed in").assertExists()
        compose.onNodeWithText("Fixture data").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shell.png")
    }

    @Test
    fun fixtureModeIsLabelled() {
        compose.setContent { MailVerdictTheme(dynamicColor = false) { ShellScreen(fixtureMode = true) } }

        compose.onNodeWithText("Fixture data").assertExists()
    }
}
