package com.frederikberg.mailverdict.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.contract.PendingSendResponse
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.compose.ApiComposerBackend
import com.frederikberg.mailverdict.kit.compose.ComposeAttachment
import com.frederikberg.mailverdict.kit.compose.ComposeIntent
import com.frederikberg.mailverdict.kit.compose.ComposerPhase
import com.frederikberg.mailverdict.kit.compose.ComposerStore
import com.frederikberg.mailverdict.kit.compose.RecipientField
import com.frederikberg.mailverdict.kit.compose.RecipientSuggestions
import com.frederikberg.mailverdict.kit.fixtures.FixtureInterceptor
import com.frederikberg.mailverdict.kit.fixtures.Fixtures
import com.frederikberg.mailverdict.kit.fixtures.MailFixtures
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/** The composer against the canned server, which records what it is sent. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ComposeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val server = FixtureInterceptor(Fixtures.routes)
    private val api =
        ApiClient(
            RequestFactory("https://fixture.invalid", "Test phone") { AuthMode.None },
            OkHttpClient.Builder().addInterceptor(server).build(),
        )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closings = mutableListOf<ComposeClosing>()

    @After
    fun stop() = scope.cancel()

    private fun open(kind: ComposeIntent.Kind): ComposerStore {
        val store = ComposerStore(ComposeIntent(kind), ApiComposerBackend(api), scope, zone = ZoneOffset.UTC)
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                val screenScope = rememberCoroutineScope()
                ComposeStoreScreen(
                    store = store,
                    suggestions = RecipientSuggestions(ApiComposerBackend(api)::searchContacts, screenScope),
                    onAttach = {},
                    onClosed = { closings += it },
                )
            }
        }
        compose.waitUntil(10_000) { store.state.value.phase == ComposerPhase.Editing }
        compose.waitForIdle()
        return store
    }

    private fun SemanticsNodeInteractionsProvider.waitFor(
        text: String,
        substring: Boolean = false,
    ) = compose.waitUntil(
        10_000,
    ) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun outbox(): List<FixtureInterceptor.Recorded> = server.recorded.filter { it.key == "POST /api/outbox" }

    private fun json(recorded: FixtureInterceptor.Recorded): JsonObject =
        Json
            .parseToJsonElement(
                recorded.body.decodeToString(),
            ).jsonObject

    @Test
    fun aFilledNewMessageSendsTheRequestTheWebSends() {
        open(ComposeIntent.Kind.New(Fixtures.PRIVATE_ACCOUNT))
        compose.onNodeWithTag(ComposeTags.field(RecipientField.To)).performTextInput("ada@example.com,")
        compose.onNodeWithTag(ComposeTags.chip(RecipientField.To, "ada@example.com")).assertExists()
        compose.onNodeWithTag(ComposeTags.SUBJECT).performTextInput("Friday")
        compose.onNodeWithTag(ComposeTags.BODY).performTextInput("See you at ten.\n\nAlex")
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compose-new.png")

        compose.onNodeWithTag(ComposeTags.SEND).performClick()
        compose.waitUntil(10_000) { closings.isNotEmpty() }

        val body = json(outbox().single())
        assertEquals(JsonPrimitive(Fixtures.PRIVATE_ACCOUNT), body["account_id"])
        assertEquals(JsonPrimitive("send"), body["kind"])
        assertEquals(listOf("ada@example.com"), body["to"]!!.jsonArray.map { (it as JsonPrimitive).content })
        assertEquals(JsonPrimitive("Friday"), body["subject"])
        assertEquals(JsonPrimitive("See you at ten.\n\nAlex"), body["body_text"])
        assertEquals(JsonPrimitive("<p>See you at ten.</p><p><br></p><p>Alex</p>"), body["body_html"])
        assertEquals(JsonPrimitive("00000000-0000-4000-8000-0000000004a1"), body["identity_id"])
        assertTrue(body["idempotency_key"] is JsonPrimitive)
        assertEquals("Message queued for sending", closings.single().notice)
    }

    @Test
    fun aReplyOpensAddressedQuotedAndThreaded() {
        open(ComposeIntent.Kind.Reply(MESSAGE))
        compose.onNodeWithTag(ComposeTags.chip(RecipientField.To, "ada@example.com")).assertExists()
        compose.waitFor("Re: Quarterly planning")
        compose.waitFor("On Sat, 14 Nov 2026, 08:12, Ada Lovelace wrote:")
        compose.onNodeWithTag(ComposeTags.QUOTE_TOGGLE).performClick()
        compose.waitFor("I put the three options", substring = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compose-reply.png")

        compose.onNodeWithTag(ComposeTags.BODY).performTextInput("Option two.")
        compose.onNodeWithTag(ComposeTags.SEND).performClick()
        compose.waitUntil(10_000) { closings.isNotEmpty() }
        val body = json(outbox().single())
        assertEquals(JsonPrimitive("<fixture-1@example.com>"), body["in_reply_to"])
        assertTrue(body["body_html"].toString().contains("gmail_quote"))
        assertTrue(body["body_text"].toString().startsWith("\"Option two.\\n\\nOn Sat, 14 Nov 2026"))
    }

    @Test
    fun leavingWithUnsavedWorkSavesADraft() {
        open(ComposeIntent.Kind.Reply(MESSAGE))
        compose.onNodeWithTag(ComposeTags.BODY).performTextInput("Half a thought")
        compose.onNodeWithTag(ComposeTags.CLOSE).performClick()
        compose.waitUntil(10_000) { closings.isNotEmpty() }

        val body = json(outbox().single())
        assertEquals(JsonPrimitive("draft"), body["kind"])
        assertEquals(JsonPrimitive("<fixture-1@example.com>"), body["in_reply_to"])
        assertEquals("Draft saved", closings.single().notice)
    }

    @Test
    fun anUntouchedComposerClosesWithoutSaving() {
        open(ComposeIntent.Kind.Reply(MESSAGE))
        compose.onNodeWithTag(ComposeTags.CLOSE).performClick()
        compose.waitUntil(10_000) { closings.isNotEmpty() }
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun aSavedDraftReopensWithItsFieldsAndReplacesItselfWhenSaved() {
        open(ComposeIntent.Kind.Draft(MailFixtures.DRAFT_ID))
        compose.onNodeWithTag(ComposeTags.chip(RecipientField.To, "Ada Lovelace <ada@example.com>")).assertExists()
        compose.waitFor("Draft")
        compose.waitFor("Option two works for me if we move the review.")
        compose.waitFor("On Sat, 14 Nov 2026, 08:12, Ada Lovelace wrote:")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compose-draft.png")

        compose.onNodeWithTag(ComposeTags.MENU).performClick()
        compose.onNodeWithTag(ComposeTags.SAVE_DRAFT).performClick()
        compose.waitUntil(10_000) { closings.isNotEmpty() }
        val body = json(outbox().single())
        assertEquals(JsonPrimitive(MailFixtures.DRAFT_ID), body["replaces_message_id"])
        assertEquals(JsonPrimitive("draft"), body["kind"])
    }

    @Test
    fun anAttachedFileIsSentAndNamedInTheForm() {
        val store = open(ComposeIntent.Kind.New(Fixtures.PRIVATE_ACCOUNT))
        // The system picker is device-only; what it hands back arrives here.
        store.addAttachments(
            listOf(ComposeAttachment("floor-plan.pdf", "application/pdf", "%PDF-1.4 plan".toByteArray())),
        )
        compose.onNodeWithTag(ComposeTags.field(RecipientField.To)).performTextInput("ada@example.com")
        compose.onNodeWithTag(ComposeTags.SUBJECT).performTextInput("Plan")
        compose.waitFor("floor-plan.pdf", substring = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compose-attachment.png")

        compose.onNodeWithTag(ComposeTags.SEND).performClick()
        compose.waitUntil(10_000) { closings.isNotEmpty() }
        val recorded = outbox().single()
        assertTrue(recorded.contentType!!.startsWith("multipart/form-data"))
        val form = recorded.body.decodeToString()
        assertTrue(form, "name=\"attachments\"; filename=\"floor-plan.pdf\"" in form)
        assertTrue(form, "\"to\":[\"ada@example.com\"]" in form)
    }

    @Test
    fun aSuggestionFromTheContactsBecomesARecipient() {
        open(ComposeIntent.Kind.New(Fixtures.PRIVATE_ACCOUNT))
        compose.onNodeWithTag(ComposeTags.field(RecipientField.To)).performClick()
        compose.onNodeWithTag(ComposeTags.field(RecipientField.To)).performTextInput("ad")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(ComposeTags.suggestion("ada@example.com"))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ComposeTags.suggestion("ada@example.com")).performClick()
        compose.onNodeWithTag(ComposeTags.chip(RecipientField.To, "ada@example.com")).assertExists()
        assertTrue(server.requests.contains("GET /api/contacts/search"))
    }

    @Test
    fun sendWithoutARecipientSaysWhatIsMissing() {
        open(ComposeIntent.Kind.New(Fixtures.PRIVATE_ACCOUNT))
        compose.onNodeWithTag(ComposeTags.field(RecipientField.To)).performTextInput("ada")
        compose.onNodeWithTag(ComposeTags.SEND).performClick()
        compose.waitFor("Not a valid email address: ada")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compose-invalid.png")
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun theUndoCapsuleCountsDownWithUndo() {
        val row = PendingSendResponse("p1", Fixtures.PRIVATE_ACCOUNT, "2026-11-14T09:00:10Z", "2026-11-14T09:00:00Z")
        var undone = false
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                Box(Modifier.padding(top = 24.dp)) {
                    UndoCapsule(row, seconds = 7, account = "Private", cancelling = false, onUndo = { undone = true })
                }
            }
        }
        compose.waitFor("Sending in 7s…")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/compose-undo.png")
        compose.onNodeWithTag(UndoSendTags.undo("p1")).performClick()
        assertTrue(undone)
    }

    private companion object {
        const val MESSAGE = "00000000-0000-4000-8000-000000001001"
    }
}
