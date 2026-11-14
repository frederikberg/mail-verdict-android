package com.frederikberg.mailverdict.kit.maillist

import com.frederikberg.mailverdict.contract.BulkActionResponse
import com.frederikberg.mailverdict.contract.MessageActionResponse
import com.frederikberg.mailverdict.kit.actions.ActionQueue
import com.frederikberg.mailverdict.kit.actions.Delivery
import com.frederikberg.mailverdict.kit.actions.FakeTransport
import com.frederikberg.mailverdict.kit.actions.InMemoryIntentPersistence
import com.frederikberg.mailverdict.kit.actions.IntentRequest
import com.frederikberg.mailverdict.kit.actions.QueueNotice
import com.frederikberg.mailverdict.kit.actions.TriageAction
import com.frederikberg.mailverdict.kit.actions.UiAction
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailListActionsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listScope = ListScope.Folder("a1", "f1")
    private val transport = FakeTransport()
    private val backend = FakeMailListBackend((1..20).map { message(it, seen = false) }.reversed())
    private val queue = ActionQueue(transport, InMemoryIntentPersistence(), scope)

    @After
    fun stop() {
        scope.cancel()
    }

    private fun store() = MailListStore(listScope, backend, scope, actions = queue)

    private fun eventually(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10_000) { block() } }

    private suspend fun until(condition: suspend () -> Boolean) {
        while (!condition()) delay(5)
    }

    /** The fake server does what the actions say, so a list read afterwards agrees with them. */
    private fun serverApplies(gate: CompletableDeferred<Unit>? = null) {
        val apply: (FakeTransport.Sent) -> Unit = { sent ->
            backend.messages =
                backend.messages.mapNotNull { m ->
                    when {
                        m.id !in sent.ids -> m
                        sent.action in setOf("archive", "trash", "spam", "expunge") -> null
                        sent.action == "move" -> if (sent.target == "f1") m else null
                        sent.action == "mark_read" -> m.copy(isSeen = true)
                        sent.action == "mark_unread" -> m.copy(isSeen = false)
                        sent.action == "flag" -> m.copy(isFlagged = true)
                        else -> m
                    }
                }
        }
        transport.onMessage = {
            gate?.await()
            apply(it)
            transport.ok(it)
        }
        transport.onBulk = {
            gate?.await()
            apply(it)
            BulkActionResponse(it.action, true, it.ids.size, targetFolderId = it.target)
        }
    }

    private fun MailListStore.ids() = state.value.rows.map { it.id }

    /** Undispatched: subscribed before this returns, so a notice emitted next is not missed (the flow keeps none for latecomers). */
    private fun <T> CoroutineScope.collecting(
        flow: Flow<T>,
        block: (T) -> Unit,
    ) = launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { block(it) } }

    @Test
    fun anArchivedRowLeavesAtOnceEvenWhileTheRequestIsStillOut() =
        eventually {
            val gate = CompletableDeferred<Unit>()
            serverApplies(gate)
            val store = store()
            store.start()
            store.perform(UiAction.Archive, setOf(id(10)))
            until { !store.ids().contains(id(10)) }
            assertEquals(19, store.state.value.rows.size)
            gate.complete(Unit)
            queue.waitUntilIdle()
            until { backend.calls.size > 1 }
            delay(100)
            assertFalse("and stays gone once the server agrees", id(10) in store.ids())
        }

    @Test
    fun theRowComesBackWhenTheActionFails() =
        eventually {
            transport.onMessage = { throw ApiError.Detail("refused", 400) }
            transport.onBulk = { throw ApiError.Detail("refused", 400) }
            val store = store()
            store.start()
            store.perform(UiAction.Archive, setOf(id(10)))
            until {
                queue.state.value.failed
                    .isNotEmpty()
            }
            until { id(10) in store.ids() }
            assertEquals("back where it was", 20, store.state.value.rows.size)
        }

    @Test
    fun undoPutsTheRowBackWhereItBelongsAndTheMoveBackReachesTheServer() =
        eventually {
            serverApplies()
            val notices = mutableListOf<QueueNotice>()
            val job = collecting(queue.notices) { notices += it }
            val store = store()
            store.start()
            store.perform(UiAction.Archive, setOf(id(10)))
            until { notices.isNotEmpty() }
            val undo = notices.single() as QueueNotice.Undo
            assertEquals("Archived", undo.message)
            queue.waitUntilIdle()
            assertFalse(id(10) in store.ids())
            queue.undo(undo.ids)
            until { id(10) in store.ids() }
            assertEquals(store.ids(), store.ids().sortedDescending())
            queue.waitUntilIdle()
            val back = transport.sent.last()
            assertEquals("move", back.action)
            assertEquals("f1", back.target)
            job.cancel()
        }

    @Test
    fun aSelectionEndsWhenItsActionIsTakenAndTheRowsShowTheNewState() =
        eventually {
            serverApplies()
            val store = store()
            store.start()
            store.startSelecting(id(5))
            store.toggleSelection(id(6))
            store.perform(UiAction.MarkRead, setOf(id(5), id(6)))
            assertFalse("selection ends", store.state.value.isSelecting)
            until {
                store.state.value.rows
                    .filter { it.id == id(5) || it.id == id(6) }
                    .none { it.isUnread }
            }
        }

    @Test
    fun aRowMarkedReadInTheReaderShowsReadInTheListWhileTheRequestIsOutEvenAcrossARefresh() =
        eventually {
            val gate = CompletableDeferred<Unit>()
            serverApplies(gate)
            val store = store()
            store.start()
            queue.enqueue(IntentRequest("a1", TriageAction.MarkRead, listOf(id(7))))
            until {
                store.state.value.rows
                    .first { it.id == id(7) }
                    .isUnread
                    .not()
            }
            store.refresh()
            assertFalse(
                store.state.value.rows
                    .first { it.id == id(7) }
                    .isUnread,
            )
            gate.complete(Unit)
            queue.waitUntilIdle()
        }

    @Test
    fun aRowOpenedFromTheUnreadFilterStaysShownReadThroughARefreshUntilTheFilterChanges() =
        eventually {
            serverApplies()
            val store = store()
            store.start()
            store.setUnreadOnly(true)
            store.didOpen(id(2))
            queue.enqueue(IntentRequest("a1", TriageAction.MarkRead, listOf(id(2))))
            queue.waitUntilIdle()
            store.refresh()
            assertTrue(id(2) in store.ids())
            assertFalse(
                store.state.value.rows
                    .first { it.id == id(2) }
                    .isUnread,
            )
            store.setUnreadOnly(false)
            store.setUnreadOnly(true)
            assertFalse("a new unread list drops it", id(2) in store.ids())
        }

    @Test
    fun aSettledActionRefreshesTheListAndItsEchoOverTheStreamDoesNot() =
        eventually {
            serverApplies()
            val store = store()
            store.start()
            store.perform(UiAction.Archive, setOf(id(10)))
            queue.waitUntilIdle()
            until { backend.calls.size >= 2 }
            delay(150)
            val settledReads = backend.calls.size
            // The event for the same message that arrives right after the answer is the action's echo.
            val now = System.currentTimeMillis()
            store.applyEvents(
                listOf(
                    LiveEvent(LiveInvalidation.MailUpdated("a1", "archive1", "f1", id(10), listOf("folder_id")), now),
                ),
            )
            delay(150)
            assertEquals("the echo reads nothing", settledReads, backend.calls.size)
            // Another client's change to a message this device did not touch does refresh.
            store.applyEvents(
                listOf(LiveEvent(LiveInvalidation.MailUpdated("a1", "f1", null, id(3), listOf("is_seen")), now)),
            )
            until { backend.calls.size > settledReads }
        }

    @Test
    fun theMenuKnowsWhatItIsAskedAboutFromTheRowsAsShown() =
        eventually {
            serverApplies()
            val store = store()
            store.start()
            assertFalse(store.actionContext(setOf(id(5))).isRead)
            store.perform(UiAction.MarkRead, setOf(id(5)))
            until { store.actionContext(setOf(id(5))).isRead }
            assertFalse("a mixed selection is not all read", store.actionContext(setOf(id(5), id(6))).isRead)
            assertEquals(setOf("f1"), store.foldersOf(setOf(id(5), id(6))))
            assertEquals(setOf("a1"), store.accountsOf(setOf(id(5))))
        }

    @Test
    fun actionsOnAGroupedListExpandConversationsAndCarryTheNewestMirroredTime() =
        eventually {
            serverApplies()
            val store = store()
            store.start()
            store.perform(UiAction.Delete, setOf(id(5), id(6)))
            queue.waitUntilIdle()
            val sent = transport.sent.single()
            assertEquals("bulk", sent.kind)
            assertTrue(sent.expandThreads)
            assertEquals("trash", sent.action)
            assertNotNull(sent.seenThrough)
            assertEquals(
                Delivery.Bulk(true),
                queue.state.value.intents
                    .single()
                    .delivery,
            )
        }

    @Test
    fun aMessageAlreadyMovedByAnotherClientIsNotMovedAgainAndSaysSo() =
        eventually {
            val notices = mutableListOf<QueueNotice>()
            val job = collecting(queue.notices) { notices += it }
            transport.onMessage = { MessageActionResponse(it.ids.first(), it.action, success = true, applied = false) }
            transport.onBulk = { BulkActionResponse(it.action, true, 0, skippedIds = it.ids) }
            val store = store()
            store.start()
            store.perform(UiAction.Archive, setOf(id(10)))
            until { notices.any { it is QueueNotice.Info } }
            until { id(10) in store.ids() }
            job.cancel()
        }
}
