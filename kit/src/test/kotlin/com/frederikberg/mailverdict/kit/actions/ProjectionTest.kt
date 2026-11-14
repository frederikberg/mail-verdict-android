package com.frederikberg.mailverdict.kit.actions

import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.contract.MessageSummary
import com.frederikberg.mailverdict.kit.maillist.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {
    private val scope = ProjectionScope(setOf("f1"), threaded = false)

    private fun rows(vararg n: Int) = n.map { message(it, seen = false) }

    private fun intent(
        action: TriageAction,
        vararg ids: String,
        target: String? = null,
        state: IntentPhase = IntentPhase.Pending,
        sequence: Int? = null,
        snapshots: List<MessageSummary> = emptyList(),
        undoRequested: Boolean = false,
    ) = MailIntent(
        id = "i-${action.raw}-${ids.joinToString()}",
        accountId = "a1",
        action = action,
        targetFolderId = target,
        messageIds = ids.toList(),
        delivery = Delivery.Message,
        snapshots = snapshots,
        createdAtMs = 0,
        state = state,
        settledSequence = sequence,
        undoRequested = undoRequested,
    )

    private fun id(n: Int) = message(n).id

    @Test
    fun aReadAndAStarShowOnTheRowAtOnce() {
        val result =
            IntentProjection.rows(
                rows(3, 2),
                listOf(intent(TriageAction.MarkRead, id(3)), intent(TriageAction.Flag, id(2))),
                0,
                scope,
            )
        assertTrue(result[0].isSeen)
        assertFalse(result[0].isFlagged)
        assertTrue(result[1].isFlagged)
        assertFalse(result[1].isSeen)
    }

    @Test
    fun anArchivedOrDeletedRowLeavesTheList() {
        for (action in listOf(TriageAction.Archive, TriageAction.Trash, TriageAction.Spam, TriageAction.Expunge)) {
            val result = IntentProjection.rows(rows(3, 2, 1), listOf(intent(action, id(2))), 0, scope)
            assertEquals(action.raw, listOf(id(3), id(1)), result.map { it.id })
        }
    }

    @Test
    fun aMoveKeepsItsRowOnlyWhenTheTargetIsAFolderTheListShows() {
        val within =
            IntentProjection.rows(
                rows(3, 2),
                listOf(intent(TriageAction.Move, id(2), target = "f1")),
                0,
                scope,
            )
        assertEquals(2, within.size)
        val away =
            IntentProjection.rows(
                rows(3, 2),
                listOf(intent(TriageAction.Move, id(2), target = "other")),
                0,
                scope,
            )
        assertEquals(listOf(id(3)), away.map { it.id })
    }

    @Test
    fun aGroupedRowsUnreadCountMovesWithItsOwnMessageAndNeverBelowZero() {
        val grouped = scope.copy(threaded = true)
        val row = message(3, seen = false).copy(unreadInThread = 2)
        val read =
            IntentProjection
                .rows(
                    listOf(row),
                    listOf(intent(TriageAction.MarkRead, row.id)),
                    0,
                    grouped,
                ).single()
        assertEquals(1, read.unreadInThread)
        val twice =
            IntentProjection
                .rows(
                    listOf(read),
                    listOf(intent(TriageAction.MarkRead, row.id)),
                    0,
                    grouped,
                ).single()
        assertEquals("a row already read counts nothing more", 1, twice.unreadInThread)
        val unread =
            IntentProjection
                .rows(
                    listOf(message(3, seen = true).copy(unreadInThread = 0)),
                    listOf(intent(TriageAction.MarkUnread, row.id)),
                    0,
                    grouped,
                ).single()
        assertEquals(1, unread.unreadInThread)
    }

    @Test
    fun aFailedOrUndoneIntentNeverApplies() {
        val failed = intent(TriageAction.Archive, id(2), state = IntentPhase.Failed)
        val undone = intent(TriageAction.Archive, id(1), state = IntentPhase.Sending, undoRequested = true)
        assertEquals(3, IntentProjection.rows(rows(3, 2, 1), listOf(failed, undone), 0, scope).size)
    }

    @Test
    fun aDoneIntentAppliesOnlyOverDataReadBeforeItSettled() {
        val done = intent(TriageAction.Archive, id(2), state = IntentPhase.Done, sequence = 5)
        assertEquals(
            "read before the server had it",
            2,
            IntentProjection.rows(rows(3, 2, 1), listOf(done), 4, scope).size,
        )
        assertEquals(
            "read after: the server already agrees",
            3,
            IntentProjection.rows(rows(3, 2, 1), listOf(done), 5, scope).size,
        )
    }

    @Test
    fun anUndoneMoveBringsItsRowBackWhereItBelongs() {
        val rows = rows(3, 1)
        val snapshot = message(2, seen = false)
        val archive = intent(TriageAction.Archive, id(2), snapshots = listOf(snapshot))
        val back =
            intent(
                TriageAction.Move,
                id(2),
                target = "f1",
                snapshots = listOf(snapshot),
            ).copy(undoes = archive.id)
        val result = IntentProjection.rows(rows, listOf(archive, back), 0, scope)
        assertEquals(listOf(id(3), id(2), id(1)), result.map { it.id })
    }

    @Test
    fun aRowPutBackPastAnEdgeOfAPartialWindowIsNotInvented() {
        val snapshot = message(1, seen = false)
        val back = intent(TriageAction.Move, id(1), target = "f1", snapshots = listOf(snapshot))
        val result = IntentProjection.rows(rows(3, 2), listOf(back), 0, scope.copy(hasOlder = true))
        assertEquals(2, result.size)
    }

    @Test
    fun theReaderSeesReadAndStarToo() {
        val detail =
            MessageDetail(
                id = "m1",
                accountId = "a1",
                folderId = "f1",
                threadId = "t",
                createdAt = "2026-11-01T00:00:00+00:00",
                isSeen = false,
            )
        val result =
            IntentProjection.message(
                detail,
                listOf(intent(TriageAction.MarkRead, "m1"), intent(TriageAction.Flag, "m1")),
                0,
            )
        assertTrue(result.isSeen && result.isFlagged)
        val back =
            IntentProjection.message(
                result,
                listOf(intent(TriageAction.MarkRead, "m1"), intent(TriageAction.MarkUnread, "m1")),
                0,
            )
        assertFalse("the later action wins", back.isSeen)
    }
}
