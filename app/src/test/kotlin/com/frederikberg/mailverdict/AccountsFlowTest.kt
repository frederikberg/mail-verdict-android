package com.frederikberg.mailverdict

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.frederikberg.mailverdict.accounts.AccountDetailTags
import com.frederikberg.mailverdict.accounts.AccountsTags
import com.frederikberg.mailverdict.kit.fixtures.Fixtures
import com.frederikberg.mailverdict.mailboxes.MailboxesTags
import com.frederikberg.mailverdict.rules.RulesTags
import com.frederikberg.mailverdict.settings.SettingsTags
import com.frederikberg.mailverdict.signins.SignInsTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The account and settings screens reached the way a person reaches them, on canned data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class AccountsFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun launchFixture(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(FixtureMode.EXTRA, true),
        )

    private fun waitForTag(tag: String) =
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForText(text: String) =
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun theOverviewLeadsToAnAccountWithItsIdentities() {
        launchFixture().use {
            waitForTag(MailboxesTags.LIST)
            compose.onNodeWithTag(MailboxesTags.LIST).performScrollToNode(hasTestTag(MailboxesTags.ACCOUNTS))
            compose.onNodeWithTag(MailboxesTags.ACCOUNTS).performClick()
            waitForTag(AccountsTags.LIST)

            compose.onNodeWithTag(AccountsTags.row(Fixtures.PRIVATE_ACCOUNT)).performClick()
            waitForTag(AccountDetailTags.SYNC_NOW)
            waitForText("alex@team.example.com")
        }
    }

    @Test
    fun settingsLeadToTheRulesTheMailSettingsAndTheSignedInDevices() {
        launchFixture().use {
            waitForTag(MailboxesTags.SETTINGS)
            compose.onNodeWithTag(MailboxesTags.SETTINGS).performClick()

            waitForTag(SettingsTags.RULES)
            compose.onNodeWithTag(SettingsTags.RULES).performClick()
            waitForTag(RulesTags.LIST)
            waitForText("File receipts")
            it.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }

            waitForTag(SettingsTags.MAIL_SETTINGS)
            compose.onNodeWithTag(SettingsTags.MAIL_SETTINGS).performClick()
            waitForText("Mark as read when filed")
            it.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }

            waitForTag(SettingsTags.SIGN_INS)
            compose.onNodeWithTag(SettingsTags.SIGN_INS).performClick()
            waitForTag(SignInsTags.LIST)
            waitForText("Pixel 8")
        }
    }
}
