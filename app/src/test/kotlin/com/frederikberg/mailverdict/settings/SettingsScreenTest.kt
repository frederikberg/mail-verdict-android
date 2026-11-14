package com.frederikberg.mailverdict.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.frederikberg.mailverdict.contract.MeResponse
import com.frederikberg.mailverdict.contract.UserRole
import com.frederikberg.mailverdict.platform.ThemeChoice
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val me = MeResponse("u1", "alex@example.com", "Alex Example", UserRole.User, false)

    private fun show(me: MeResponse? = this.me) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                SettingsScreen(
                    theme = ThemeChoice.System,
                    serverUrl = "https://mail.example.com",
                    me = me,
                    versionName = "0.1.0",
                    onTheme = { calls += "theme:$it" },
                    onSignOut = { calls += "sign-out" },
                    onBack = { calls += "back" },
                )
            }
        }
    }

    @Test
    fun theConnectionSectionNamesTheServerThePersonAndTheirRole() {
        show()

        compose.onNodeWithText("https://mail.example.com").assertExists()
        compose.onNodeWithText("Alex Example").assertExists()
        compose.onNodeWithText("alex@example.com").assertExists()
        compose.onNodeWithText("User").assertExists()
        compose.onNodeWithText("0.1.0").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings.png")
    }

    @Test
    fun beforeTheServerHasSaidWhoTheyAreOnlyTheServerShows() {
        show(me = null)

        compose.onNodeWithText("Signed in as").assertDoesNotExist()
        compose.onNodeWithText("https://mail.example.com").assertExists()
    }

    @Test
    fun anAdminIsLabelledAsOne() {
        show(me.copy(role = UserRole.Admin))

        compose.onNodeWithText("Administrator").assertExists()
    }

    @Test
    fun pickingAThemeApplies() {
        show()

        compose.onNodeWithText("Dark").performClick()

        assertEquals(listOf("theme:Dark"), calls)
    }

    @Test
    fun signingOutAsksFirstAndOnlyThenSignsOut() {
        show()

        compose.onNodeWithTag(SettingsTags.SIGN_OUT).performClick()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithText("Sign out?").assertExists()
        compose.onNodeWithTag(SettingsTags.CONFIRM_SIGN_OUT).performClick()

        assertEquals(listOf("sign-out"), calls)
    }

    @Test
    fun cancellingTheConfirmationKeepsThePersonSignedIn() {
        show()

        compose.onNodeWithTag(SettingsTags.SIGN_OUT).performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithText("Sign out?").assertDoesNotExist()
    }
}
