package com.frederikberg.mailverdict.kit.actions

import com.frederikberg.mailverdict.contract.FolderKind
import com.frederikberg.mailverdict.contract.FolderOrderItem
import com.frederikberg.mailverdict.contract.FolderOrderResponse
import com.frederikberg.mailverdict.kit.maillist.message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageActionSetTest {
    private fun context(
        read: Boolean = false,
        starred: Boolean = false,
        trash: Boolean = false,
        junk: Boolean = false,
        glacier: Boolean = false,
        verdict: Boolean? = null,
    ) = ActionContext(read, starred, trash, junk, glacier, verdict)

    private fun MessageActionSetResult.actions() = groups.flatMap { it.actions }

    private class MessageActionSetResult(
        val groups: List<ActionGroup>,
    )

    private fun set(context: ActionContext) = MessageActionSetResult(MessageActionSet.groups(context))

    @Test
    fun theStateActionsFlipWithTheState() {
        assertEquals(
            listOf(UiAction.MarkRead, UiAction.Star, UiAction.MoveTo, UiAction.MoveToJunk, UiAction.Archive),
            set(context()).groups.first { it.kind == ActionGroup.Kind.State }.actions,
        )
        assertEquals(
            listOf(UiAction.MarkUnread, UiAction.Unstar, UiAction.MoveTo, UiAction.NotJunk, UiAction.Archive),
            set(
                context(read = true, starred = true, junk = true),
            ).groups.first { it.kind == ActionGroup.Kind.State }.actions,
        )
    }

    @Test
    fun deleteIsPermanentInTrashAndInTheGlacierAndOnlyThere() {
        assertEquals(UiAction.Delete, MessageActionSet.destructive(context()))
        assertEquals(UiAction.DeleteForever, MessageActionSet.destructive(context(trash = true)))
        assertEquals(UiAction.DeleteForever, MessageActionSet.destructive(context(glacier = true)))
        assertTrue(set(context(trash = true)).actions().contains(UiAction.DeleteForever))
        assertFalse(set(context(trash = true)).actions().contains(UiAction.Delete))
    }

    @Test
    fun noSpamRulingIsOfferedForAGlaciedMessage() {
        val actions = set(context(glacier = true)).actions()
        assertFalse(UiAction.MoveToJunk in actions || UiAction.NotJunk in actions)
    }

    @Test
    fun theVerdictGroupExistsOnlyForAMessageWithAVerdict() {
        assertFalse(set(context()).groups.any { it.kind == ActionGroup.Kind.Verdict })
        assertEquals(
            listOf(UiAction.ConfirmVerdict, UiAction.CorrectVerdict),
            set(context(verdict = true)).groups.first { it.kind == ActionGroup.Kind.Verdict }.actions,
        )
    }

    @Test
    fun theSwipesArchiveToTheRightAndDeleteToTheLeft() {
        assertEquals(UiAction.Archive, MessageActionSet.swipeEnd(context()))
        assertEquals(UiAction.Delete, MessageActionSet.swipeStart(context()))
        assertEquals(UiAction.DeleteForever, MessageActionSet.swipeStart(context(trash = true)))
    }

    @Test
    fun everyQueuedUiActionMapsToItsServerAction() {
        val expected =
            mapOf(
                UiAction.MarkRead to "mark_read",
                UiAction.MarkUnread to "mark_unread",
                UiAction.Star to "flag",
                UiAction.Unstar to "unflag",
                UiAction.Archive to "archive",
                UiAction.MoveToJunk to "spam",
                UiAction.NotJunk to "not_spam",
                UiAction.Delete to "trash",
                UiAction.DeleteForever to "expunge",
                UiAction.MoveTo to "move",
            )
        expected.forEach { (ui, raw) -> assertEquals(ui.name, raw, ui.action?.raw) }
        assertNull(UiAction.ConfirmVerdict.action)
    }

    @Test
    fun anUngroupedSingleRowIsAMessageActionGuardedByItsFolder() {
        val request =
            TriageRequests
                .forRows(
                    listOf(message(1, folder = "f1")),
                    UiAction.Archive,
                    threaded = false,
                ).single()
        assertEquals(Delivery.Message, request.delivery)
        assertEquals(mapOf(message(1).id to "f1"), request.originFolderIds)
        assertFalse(request.confirm)
    }

    @Test
    fun groupedRowsAreAConversationBulkCarryingTheNewestMirroredTime() {
        val rows =
            listOf(
                message(1).copy(mirroredAt = "2026-11-01T10:00:00+00:00"),
                message(2).copy(mirroredAt = "2026-11-02T10:00:00+00:00"),
            )
        val request = TriageRequests.forRows(rows, UiAction.Delete, threaded = true).single()
        assertEquals(Delivery.Bulk(true), request.delivery)
        assertEquals("2026-11-02T10:00:00+00:00", request.seenThrough)
        assertEquals(rows, request.snapshots)
    }

    @Test
    fun aSelectionAcrossAccountsBecomesOneRequestPerAccount() {
        val rows = listOf(message(1), message(2).copy(accountId = "a2"), message(3))
        val requests = TriageRequests.forRows(rows, UiAction.MarkRead, threaded = false)
        assertEquals(setOf("a1", "a2"), requests.map { it.accountId }.toSet())
        assertEquals(listOf(message(1).id, message(3).id), requests.first { it.accountId == "a1" }.messageIds)
        assertEquals(Delivery.Message, requests.first { it.accountId == "a2" }.delivery)
    }

    @Test
    fun aMoveNeedsATargetAndOnlyAMoveCarriesOne() {
        assertTrue(TriageRequests.forRows(listOf(message(1)), UiAction.MoveTo, false).isEmpty())
        assertEquals(
            "f9",
            TriageRequests.forRows(listOf(message(1)), UiAction.MoveTo, false, "f9").single().targetFolderId,
        )
        assertNull(TriageRequests.forRows(listOf(message(1)), UiAction.Archive, false, "f9").single().targetFolderId)
    }

    @Test
    fun deleteForeverCarriesItsConfirmationAndNothingElseDoes() {
        assertTrue(TriageRequests.forRows(listOf(message(1)), UiAction.DeleteForever, false).single().confirm)
        assertFalse(TriageRequests.forRows(listOf(message(1)), UiAction.Delete, false).single().confirm)
    }

    @Test
    fun theUndoOfferSaysWhatHappenedAndCountsMoreThanOne() {
        assertEquals("Archived", TriageRequests.undoNotice(TriageAction.Archive, 1))
        assertEquals("Moved to Trash · 3 messages", TriageRequests.undoNotice(TriageAction.Trash, 3))
        assertNull("permanent deletion has no undo", TriageRequests.undoNotice(TriageAction.Expunge, 1))
    }

    @Test
    fun theMovePickerOffersFoldersWithoutTheGlacierRecentFirstAndNeverTheCurrentFolder() {
        val order =
            FolderOrderResponse(
                listOf(
                    FolderOrderItem("inbox", "INBOX", specialUse = "inbox"),
                    FolderOrderItem("projects", "Projects"),
                    FolderOrderItem("archive", "Archive", specialUse = "archive"),
                    FolderOrderItem("glacier", "Glacier", kind = FolderKind.Glacier),
                ),
            )
        val targets = MovePicker.targets("a1", order)
        assertEquals(listOf("Inbox", "Projects", "Archive"), targets.map { it.name })
        assertEquals(
            listOf("Archive", "Projects"),
            MovePicker.ordered(targets, setOf("inbox"), recentIds = listOf("archive"), query = "").map { it.name },
        )
        assertEquals(listOf("Projects"), MovePicker.ordered(targets, emptySet(), emptyList(), " PROJ ").map { it.name })
    }

    @Test
    fun onlyTheFiveMostRecentTargetsAreRemembered() {
        val recent = InMemoryRecentMoveTargets()
        (1..7).forEach { recent.record("a1", "f$it") }
        recent.record("a1", "f5")
        assertEquals(listOf("f5", "f7", "f6", "f4", "f3"), recent.recentIds("a1"))
    }
}
