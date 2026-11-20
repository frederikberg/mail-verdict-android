package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.contract.AttachmentSummary
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerStoreTest {
    private val backend = FakeComposerBackend()
    private val recovery = MemoryRecovery()

    private fun TestScope.store(
        kind: ComposeIntent.Kind,
        restoration: UndoRestoration? = null,
    ) = ComposerStore(ComposeIntent(kind), backend, backgroundScope, recovery, restoration, ZoneOffset.UTC)

    private fun ComposerStore.type(
        field: RecipientField,
        text: String,
    ) = setRecipientText(field, text)

    @Test
    fun aReplySendsAsTheIdentityTheMessageWasAddressedToAndOffersOnlyItsAccount() =
        runTest {
            backend.messages["m1"] = FakeComposerBackend.message("m1")
            val store = store(ComposeIntent.Kind.Reply("m1"))
            store.load()

            val state = store.state.value
            assertEquals(ComposerPhase.Editing, state.phase)
            assertEquals(listOf("ada@example.com"), state.recipients(RecipientField.To))
            assertEquals("Re: Plan", state.subject)
            assertEquals("i2", state.selectedFrom?.key)
            assertEquals(listOf("i1", "i2"), state.fromOptions.map { it.key })
            assertEquals("On Sat, 14 Nov 2026, 09:00, Ada wrote:", state.quote?.attribution)
            assertFalse(state.isDirty)
        }

    @Test
    fun aNewMessageStartsFromTheAccountBeingReadAndCanPickAnyAddress() =
        runTest {
            val store = store(ComposeIntent.Kind.New("a2"))
            store.load()
            val state = store.state.value
            assertEquals("account-default:a2", state.selectedFrom?.key)
            assertTrue(state.showsFromPicker)
            assertEquals(3, state.fromOptions.size)
        }

    @Test
    fun sendNeedsAToRecipientAndRefusesTextThatIsNotAnAddress() =
        runTest {
            val store = store(ComposeIntent.Kind.New())
            store.load()

            assertEquals(SubmitOutcome.Blocked, store.submit(draft = false))
            assertEquals("Add at least one recipient", store.state.value.sendHint)

            store.type(RecipientField.To, "not an address")
            assertEquals(SubmitOutcome.Blocked, store.submit(draft = false))
            assertEquals(
                "Not a valid email address: not an address",
                store.state.value.recipientNotes[RecipientField.To],
            )
            assertEquals("not an address", store.recipientText(RecipientField.To))
            assertTrue(backend.submitted.isEmpty())
        }

    @Test
    fun typedTextIsCommittedOnSendAndTheRequestIsTheOneTheWebSends() =
        runTest {
            val store = store(ComposeIntent.Kind.New("a1"))
            store.load()
            store.type(RecipientField.To, "ada@example.com, ")
            store.type(RecipientField.To, "grace@example.com")
            store.setSubject("Plan")
            store.setBody("Hello\n\nAlex")

            assertEquals(SubmitOutcome.Sent, store.submit(draft = false))
            val (request, uploads) = backend.submitted.single()
            assertEquals("send", request.kind)
            assertEquals(listOf("ada@example.com", "grace@example.com"), request.to)
            assertEquals("i1", request.identityId)
            assertEquals("Hello\n\nAlex", request.bodyText)
            assertEquals("<p>Hello</p><p><br></p><p>Alex</p>", request.bodyHtml)
            assertTrue(uploads.isEmpty())
        }

    @Test
    fun aSecondSubmitWhileTheFirstIsInFlightDoesNothing() =
        runTest {
            val store = store(ComposeIntent.Kind.New("a1"))
            store.load()
            store.type(RecipientField.To, "ada@example.com")
            backend.gate = CompletableDeferred()
            val first = async { store.submit(draft = false) }
            runCurrent()
            assertEquals(ComposerPhase.Sending, store.state.value.phase)
            assertEquals(SubmitOutcome.Ignored, store.submit(draft = false))
            assertEquals(SubmitOutcome.Ignored, store.submit(draft = true))
            backend.gate!!.complete(Unit)
            assertEquals(SubmitOutcome.Sent, first.await())
            assertEquals(1, backend.submitted.size)
            assertEquals(SubmitOutcome.Ignored, store.submit(draft = false))
        }

    @Test
    fun aFailedSendComesBackEditableWithTheReason() =
        runTest {
            val store = store(ComposeIntent.Kind.New("a1"))
            store.load()
            store.type(RecipientField.To, "ada@example.com")
            backend.answer = { throw ApiError.Transport("Could not reach the server.") }
            assertEquals(SubmitOutcome.Failed("Not sent: Could not reach the server."), store.submit(draft = false))
            assertEquals(ComposerPhase.Editing, store.state.value.phase)
            assertEquals("Not sent: Could not reach the server.", store.state.value.submitError)
            assertTrue(store.state.value.isDirty)
        }

    @Test
    fun leavingSavesUnsavedWorkAsADraftAndAnUntouchedComposerJustCloses() =
        runTest {
            backend.messages["m1"] = FakeComposerBackend.message("m1")
            val untouched = store(ComposeIntent.Kind.Reply("m1"))
            untouched.load()
            assertEquals(LeaveOutcome.Close(null), untouched.leave())
            assertTrue(backend.submitted.isEmpty())

            val written = store(ComposeIntent.Kind.Reply("m1"))
            written.load()
            written.setBody("Option two.")
            assertEquals(LeaveOutcome.Close(ComposerStore.DRAFT_SAVED), written.leave())
            val request = backend.submitted.single().first
            assertEquals("draft", request.kind)
            assertEquals("<m1@example.com>", request.inReplyTo)
            assertEquals("Option two.\n\nOn Sat, 14 Nov 2026, 09:00, Ada wrote:\n> Which one?", request.bodyText)
        }

    @Test
    fun aDraftThatCannotBeSavedKeepsTheComposerOpen() =
        runTest {
            val store = store(ComposeIntent.Kind.New("a1"))
            store.load()
            store.setSubject("Half written")
            backend.answer = { throw ApiError.Transport("Could not reach the server.") }
            assertEquals(LeaveOutcome.Stay("Not saved: Could not reach the server."), store.leave())
        }

    @Test
    fun aReopenedDraftRestoresItsFieldsQuoteAndFilesAndReplacesItself() =
        runTest {
            backend.messages["d1"] =
                FakeComposerBackend.message(
                    "d1",
                    from = "alias@one.test",
                    to = listOf("ada@example.com"),
                    subject = "Re: Plan",
                    body = "Two.\n\nOn a day, Ada wrote:\n> Which one?",
                    draft = true,
                    attachments = listOf(AttachmentSummary("f1", "plan.pdf", "application/pdf", 3)),
                )
            backend.quotes["d1"] =
                "<p><strong>Two.</strong></p><div class=\"gmail_quote\">" +
                "<div class=\"gmail_attr\">On a day, Ada wrote:</div>" +
                "<blockquote class=\"gmail_quote\"><p>Which one?</p></blockquote></div>"
            backend.files["f1"] = "PDF".toByteArray()
            val store = store(ComposeIntent.Kind.Draft("d1"))
            store.load()

            val state = store.state.value
            assertEquals("Draft", state.title)
            assertEquals(listOf("ada@example.com"), state.recipients(RecipientField.To))
            assertEquals("Two.", state.body)
            assertEquals("i2", state.selectedFrom?.key)
            assertEquals(ComposeQuote("<p>Which one?</p>", "On a day, Ada wrote:"), state.quote)
            assertEquals(listOf("plan.pdf"), state.attachments.map { it.filename })

            store.setSubject("Re: Plan, again")
            store.submit(draft = true)
            val (request, uploads) = backend.submitted.single()
            assertEquals("d1", request.replacesMessageId)
            assertTrue(
                "an unchanged body keeps the HTML it came in as",
                request.bodyHtml!!.startsWith("<p><strong>Two.</strong></p><div"),
            )
            assertEquals("Two.\n\nOn a day, Ada wrote:\n> Which one?", request.bodyText)
            assertEquals(listOf("plan.pdf"), uploads.map { it.filename })
        }

    @Test
    fun anEditedDraftBodyIsWrittenAgainFromItsText() =
        runTest {
            backend.messages["d1"] = FakeComposerBackend.message("d1", draft = true)
            backend.quotes["d1"] = "<p><em>Old</em></p>"
            val store = store(ComposeIntent.Kind.Draft("d1"))
            store.load()
            store.setBody("New")
            store.submit(draft = true)
            assertEquals(
                "<p>New</p>",
                backend.submitted
                    .single()
                    .first.bodyHtml,
            )
        }

    @Test
    fun aForwardCarriesTheOriginalsFilesAndNamesTheOnesItCouldNotFetch() =
        runTest {
            backend.messages["m1"] =
                FakeComposerBackend.message(
                    "m1",
                    attachments = listOf(AttachmentSummary("f1", "a.pdf"), AttachmentSummary("f2", "b.pdf")),
                )
            backend.files["f1"] = "A".toByteArray()
            val store = store(ComposeIntent.Kind.Forward("m1"))
            store.load()
            val state = store.state.value
            assertEquals("Fwd: Plan", state.subject)
            assertEquals(listOf("a.pdf"), state.attachments.map { it.filename })
            assertEquals(listOf("b.pdf"), state.unrestoredAttachments)
            assertTrue(state.recipients(RecipientField.To).isEmpty())
        }

    @Test
    fun theRecoverySnapshotIsWrittenOncePerSecondWhileUnsavedAndClearedBySending() =
        runTest {
            val store = store(ComposeIntent.Kind.New("a1"))
            store.load()
            store.startRecovery()

            advanceTimeBy(1_001)
            assertEquals("nothing unsaved, nothing written", 0, recovery.writes)

            store.setBody("Hello")
            advanceTimeBy(999)
            assertEquals(0, recovery.writes)
            advanceTimeBy(2)
            assertEquals(1, recovery.writes)
            assertEquals("Hello", recovery.stored["new"]?.body)

            advanceTimeBy(3_000)
            assertEquals("an unchanged composer is not written again", 1, recovery.writes)

            store.type(RecipientField.To, "ada@example.com")
            store.submit(draft = false)
            assertNull(recovery.stored["new"])
            advanceTimeBy(3_000)
            assertEquals(1, recovery.writes)
            store.stop()
        }

    @Test
    fun aSnapshotLeftBehindIsOfferedAndRestoredAsUnsaved() =
        runTest {
            recovery.stored["new"] =
                RecoveryContent(
                    listOf("ada@example.com"),
                    emptyList(),
                    listOf("b@x.test"),
                    "Plan",
                    "Draft text",
                    null,
                    emptyList(),
                )
            val store = store(ComposeIntent.Kind.New("a1"))
            store.load()
            assertTrue(store.state.value.recoverable)
            val revision = store.state.value.textRevision

            store.restoreRecovered()
            val state = store.state.value
            assertFalse(state.recoverable)
            assertEquals("Draft text", state.body)
            assertTrue(state.showsCcBcc)
            assertTrue(state.isDirty)
            assertTrue(state.textRevision > revision)
        }

    @Test
    fun aCancelledSendReopensWithEverythingItStaged() =
        runTest {
            val quote = ComposeQuote("<p>Which one?</p>", "On a day, Ada wrote:")
            val row =
                FakeComposerBackend
                    .pending(sendAfter = "2026-11-14T09:00:10Z")
                    .copy(
                        bodyHtml = ComposeHtml.body(ComposeHtml.fromText("Two."), quote),
                        inReplyTo = "<m1@example.com>",
                        fromAddr = "alias@one.test",
                    )
            val store =
                store(
                    ComposeIntent.Kind.UndoRestore("p1"),
                    UndoRestoration(row, listOf(ComposeAttachment("a.pdf", null, ByteArray(1))), listOf("b.pdf")),
                )
            store.load()
            val state = store.state.value
            assertEquals("Two.", state.body)
            assertEquals(quote, state.quote)
            assertEquals("i2", state.selectedFrom?.key)
            assertEquals(listOf("b.pdf"), state.unrestoredAttachments)
            assertTrue(state.isDirty)
            store.submit(draft = false)
            assertEquals(
                "Two.\n\nOn a day, Ada wrote:\n> Which one?",
                backend.submitted
                    .single()
                    .first.bodyText,
            )
        }

    @Test
    fun aRestoreWithNothingToRestoreFailsToLoad() =
        runTest {
            val store = store(ComposeIntent.Kind.UndoRestore("gone"))
            store.load()
            assertEquals(ComposerPhase.LoadFailed("The cancelled message could not be found."), store.state.value.phase)
        }
}
