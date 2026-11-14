package com.frederikberg.mailverdict

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.frederikberg.mailverdict.mailboxes.MailboxesTags
import com.frederikberg.mailverdict.settings.SettingsTags
import com.frederikberg.mailverdict.signin.SignInTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The whole signed-in path on canned data: overview, a folder, settings, sign-out back to the sign-in screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class MainActivityFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun launchFixture(): ActivityScenario<MainActivity> {
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(FixtureMode.EXTRA, true)
        return ActivityScenario.launch(intent)
    }

    private fun waitForText(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTextCount(text) > 0
        }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(
            androidx.compose.ui.test
                .hasText(text),
        ).fetchSemanticsNodes().size

    @Test
    fun fixtureModeOpensOnTheOverviewAndWalksToSettingsAndBackToSignIn() {
        launchFixture().use {
            waitForText("Inboxes")
            compose.onNodeWithText("Private").assertExists()

            compose.onNodeWithTag(MailboxesTags.SETTINGS).performClick()
            waitForText("Alex Example")

            compose.onNodeWithTag(SettingsTags.SIGN_OUT).performClick()
            compose.onNodeWithTag(SettingsTags.CONFIRM_SIGN_OUT).performClick()

            compose.waitUntil(timeoutMillis = 10_000) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasTestTag(SignInTags.SIGN_IN),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
    }

    @Test
    fun aFolderOpensItsPlaceholderAndBackReturnsToTheOverview() {
        launchFixture().use {
            waitForText("Inboxes")
            val state = (ApplicationProvider.getApplicationContext<MailVerdictApp>()).currentGraph()
            check(state.fixtureMode)

            compose.onNodeWithText("Archive").performClick()
            waitForText("The message list is not part of this build yet.")
        }
    }
}
