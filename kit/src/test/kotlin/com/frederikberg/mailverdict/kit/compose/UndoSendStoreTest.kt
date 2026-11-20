package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.contract.PendingSendAttachmentSummary
import com.frederikberg.mailverdict.contract.PendingSendResponse
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.RawBody
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class UndoSendStoreTest {
    private class Backend : UndoSendBackend {
        val rows = mutableListOf<PendingSendResponse>()
        val cancelled = mutableListOf<String>()

        /** Sends past their window, which the server answers 404 for. */
        val sent = mutableSetOf<String>()

        override suspend fun pending() = rows.filter { it.id !in cancelled && it.id !in sent }

        override suspend fun cancel(pendingSendId: String) {
            if (pendingSendId in sent) throw ApiError.Http(404, "already sent")
            cancelled += pendingSendId
        }

        override suspend fun pendingAttachment(
            pendingSendId: String,
            attachmentId: String,
        ) = if (attachmentId ==
            "x1"
        ) {
            RawBody("PDF".toByteArray(), "application/pdf", null)
        } else {
            throw ApiError.Http(404, "gone")
        }
    }

    private val backend = Backend()

    private fun TestScope.store() = UndoSendStore(backend, backgroundScope) { currentTime }

    /** A send staged at virtual time zero with a ten-second window. */
    private fun staged(id: String = "p1") =
        FakeComposerBackend.pending(id, Instant.ofEpochMilli(10_000).toString()).copy(
            attachments =
                listOf(
                    PendingSendAttachmentSummary("x1", "a.pdf"),
                    PendingSendAttachmentSummary("x2", "b.pdf"),
                ),
        )

    @Test
    fun cancellingInsideTheWindowTakesTheSendBackWithItsFiles() =
        runTest {
            val store = store()
            val row = staged()
            backend.rows += row
            store.add(row)

            advanceTimeBy(3_000)
            assertEquals(7, store.secondsRemaining(row))
            val outcome = store.undo("p1")

            assertEquals(listOf("p1"), backend.cancelled)
            assertTrue(outcome is UndoOutcome.Restored)
            val intent = (outcome as UndoOutcome.Restored).intent
            assertEquals(ComposeIntent.Kind.UndoRestore("p1"), intent.kind)
            val restoration = store.restoration("p1")!!
            assertEquals(listOf("a.pdf"), restoration.attachments.map { it.filename })
            assertEquals(listOf("b.pdf"), restoration.missing)
            assertTrue(store.visibleRows().isEmpty())
            store.refresh()
            assertTrue(
                "the server no longer holds it",
                store.state.value.pending
                    .isEmpty(),
            )
        }

    @Test
    fun aSendPastItsWindowLeavesTheCapsuleAndCannotBeTakenBack() =
        runTest {
            val store = store()
            val row = staged()
            store.add(row)
            advanceTimeBy(9_000)
            assertEquals(listOf(row), store.visibleRows())
            advanceTimeBy(3_001)
            assertTrue(store.visibleRows().isEmpty())

            backend.sent += "p1"
            assertEquals(UndoOutcome.TooLate, store.undo("p1"))
            assertTrue(backend.cancelled.isEmpty())
        }

    @Test
    fun anUnknownOrAlreadyCancellingSendIsIgnored() =
        runTest {
            val store = store()
            assertEquals(UndoOutcome.Ignored, store.undo("nothing"))
        }
}
