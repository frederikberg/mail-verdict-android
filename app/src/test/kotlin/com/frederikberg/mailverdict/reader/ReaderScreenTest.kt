package com.frederikberg.mailverdict.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.fixtures.FixtureInterceptor
import com.frederikberg.mailverdict.kit.fixtures.Fixtures
import com.frederikberg.mailverdict.kit.live.OfflineNotice
import com.frederikberg.mailverdict.kit.live.Reachability
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.kit.reader.ApiReaderBackend
import com.frederikberg.mailverdict.kit.reader.ReaderPhase
import com.frederikberg.mailverdict.kit.reader.ReaderState
import com.frederikberg.mailverdict.kit.reader.ReaderStore
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ReaderScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()
    private val conversation = "00000000-0000-4000-8000-000000001001"
    private val newsletter = "00000000-0000-4000-8000-000000001002"
    private val longOne = "00000000-0000-4000-8000-000000001005"

    private fun loaded(messageId: String): ReaderState {
        val client = OkHttpClient.Builder().addInterceptor(FixtureInterceptor(Fixtures.routes)).build()
        val api = ApiClient(RequestFactory("https://fixture.invalid") { AuthMode.None }, client)
        val store = ReaderStore(messageId, emptySet(), ApiReaderBackend(api))
        runBlocking { store.load() }
        return store.state.value
    }

    private fun show(
        state: ReaderState,
        offline: OfflineNotice? = null,
    ) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                ReaderScreen(
                    state = state,
                    offline = offline,
                    web = MessageWebContext("https://fixture.invalid", { null }, { events += "navigate:$it" }),
                    onBack = { events += "back" },
                    onRetry = { events += "retry" },
                    onToggle = { events += "toggle:$it" },
                    onShowImages = { events += "images:$it" },
                    onShowFullBody = { events += "full:$it" },
                    onOpenAttachment = { message, attachment ->
                        events +=
                            "attachment:${message.id}:${attachment.filename}"
                    },
                    onDismissNotice = {},
                )
            }
        }
    }

    @Test
    fun aConversationIsNewestFirstWithOnlyTheOpenedMessageExpanded() {
        val state = loaded(conversation)
        show(state)

        assertEquals(3, state.messages.size)
        compose.onNodeWithText("Hi Alex,", substring = true).assertIsDisplayed()
        compose.onNodeWithText("First draft is in.").assertIsDisplayed()
        compose.onNodeWithText("Can you draft the options?").assertIsDisplayed()
        compose.onNodeWithText("floor-plan.pdf").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/reader-conversation.png")
    }

    @Test
    fun tappingAHeaderTogglesItAndAnAttachmentOpens() {
        val state = loaded(conversation)
        show(state)
        compose.onNodeWithTag(ReaderTags.toggle(state.messages.last().id)).performClick()
        compose
            .onNodeWithTag(
                ReaderTags.attachment(
                    state.messages
                        .first()
                        .attachments
                        .first()
                        .id,
                ),
            ).performClick()
        assertEquals(2, events.size)
        assertTrue(events[0].startsWith("toggle:"))
        assertTrue(events[1].endsWith("floor-plan.pdf"))
    }

    @Test
    fun aMessageWithBlockedImagesOffersToShowThem() {
        val state = loaded(newsletter)
        show(state)
        compose.onNodeWithText("Remote images are hidden.").assertIsDisplayed()
        compose.onNodeWithTag(ReaderTags.showImages(newsletter)).performClick()
        assertEquals(listOf("images:$newsletter"), events)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/reader-newsletter.png")
    }

    @Test
    fun aVeryLongPlainBodyIsCutAndTheRestIsOneTapAway() {
        val state = loaded(longOne)
        show(state)
        compose.onNodeWithText("The rest of this long message is not shown.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ReaderTags.showAll(longOne)).performScrollTo().performClick()
        assertEquals(listOf("full:$longOne"), events)
    }

    @Test
    fun aSavedConversationSaysSoInTheCapsule() {
        val state = loaded(conversation)
        val saved = System.currentTimeMillis() - 3 * 3_600_000
        show(state.copy(savedCopyAtMs = saved), OfflineNotice.make(Reachability.ServerUnreachable, saved))
        compose.onNodeWithText("Server unreachable · Saved 3 h ago").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/reader-offline.png")
    }

    @Test
    fun aFailureOffersARetryAndLoadingShowsProgress() {
        show(ReaderState(phase = ReaderPhase.Failed("gone"), openedId = "x"))
        compose.onNodeWithText("Could not open this message: gone").assertIsDisplayed()
        compose.onNodeWithTag(ReaderTags.RETRY).performClick()
        assertEquals(listOf("retry"), events)
    }
}
