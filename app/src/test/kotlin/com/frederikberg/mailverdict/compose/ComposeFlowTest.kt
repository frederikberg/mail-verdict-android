package com.frederikberg.mailverdict.compose

import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.frederikberg.mailverdict.FixtureMode
import com.frederikberg.mailverdict.MainActivity
import com.frederikberg.mailverdict.kit.actions.UiAction
import com.frederikberg.mailverdict.kit.compose.RecipientField
import com.frederikberg.mailverdict.maillist.MailListTags
import com.frederikberg.mailverdict.reader.ReaderTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The ways into the composer, through the whole app on canned data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ComposeFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            ).putExtra(FixtureMode.EXTRA, true),
        )

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForTag(tag: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun replyUnderAMessageOpensTheComposerAddressedAndQuoted() {
        launch().use {
            waitFor("Inboxes")
            compose.onNodeWithText("Archive").performClick()
            waitFor("Quarterly planning")
            compose.onNodeWithText("Ada Lovelace").performClick()
            waitForTag(ReaderTags.answer(UiAction.Reply, OPENED))

            compose.onNodeWithTag(ReaderTags.answer(UiAction.Reply, OPENED)).performScrollTo().performClick()
            waitForTag(ComposeTags.chip(RecipientField.To, "ada@example.com"))
            waitFor("Re: Quarterly planning")
            waitFor("On Sat, 14 Nov 2026, 08:12, Ada Lovelace wrote:")
        }
    }

    @Test
    fun theReaderMenuOffersReplyAllAndForward() {
        launch().use {
            waitFor("Inboxes")
            compose.onNodeWithText("Archive").performClick()
            waitFor("Quarterly planning")
            compose.onNodeWithText("Ada Lovelace").performClick()
            waitForTag(ReaderTags.MENU)
            compose.onNodeWithTag(ReaderTags.MENU).performClick()
            compose
                .onNodeWithTag(
                    com.frederikberg.mailverdict.actions.ActionTags
                        .menu(UiAction.Forward),
                ).performClick()
            waitFor("Fwd: Quarterly planning")
            waitFor("floor-plan.pdf", substring = true)
        }
    }

    @Test
    fun aDraftInTheDraftsFolderOpensInTheComposer() {
        launch().use {
            waitFor("Inboxes")
            compose.onNodeWithText("Drafts").performClick()
            waitFor("Re: Quarterly planning")
            compose.onNodeWithText("Re: Quarterly planning").performClick()
            waitFor("Option two works for me if we move the review.")
            compose.onNodeWithTag(ComposeTags.SEND).assertExists()
        }
    }

    @Test
    fun theListsComposeButtonOpensANewMessage() {
        launch().use {
            waitFor("Inboxes")
            compose.onNodeWithText("Archive").performClick()
            waitForTag(MailListTags.COMPOSE)
            compose.onNodeWithTag(MailListTags.COMPOSE).performClick()
            waitFor("New message")
            compose.onNodeWithTag(ComposeTags.FROM).assertExists()
        }
    }

    private fun waitFor(
        text: String,
        substring: Boolean,
    ) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        const val OPENED = "00000000-0000-4000-8000-000000001001"
    }
}
