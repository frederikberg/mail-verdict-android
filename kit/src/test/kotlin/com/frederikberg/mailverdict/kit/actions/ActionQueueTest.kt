package com.frederikberg.mailverdict.kit.actions

import com.frederikberg.mailverdict.contract.BulkActionResponse
import com.frederikberg.mailverdict.contract.BulkActionSource
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionQueueTest {
    private val transport = FakeTransport()
    private val persistence = InMemoryIntentPersistence()
    private val online = MutableStateFlow(true)
    private val connectivity =
        object : QueueConnectivity {
            override val online = this@ActionQueueTest.online
        }

    private fun TestScope.queue(
        persistence: IntentPersistence = this@ActionQueueTest.persistence,
        timing: ActionQueue.Timing = ActionQueue.Timing(),
    ) = ActionQueue(transport, persistence, backgroundScope, connectivity, { testScheduler.currentTime }, timing)

    private fun request(
        action: TriageAction,
        vararg ids: String,
        account: String = "a1",
        target: String? = null,
        origin: String = "inbox1",
        delivery: Delivery = if (ids.size == 1) Delivery.Message else Delivery.Bulk(false),
    ) = IntentRequest(account, action, ids.toList(), target, delivery, ids.associateWith { origin })

    private fun ActionQueue.byId(id: String) = state.value.intents.firstOrNull { it.id == id }

    @Test
    fun anActionIsSentOnceAndIsDoneAfterwards() =
        runTest {
            val queue = queue()
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            runCurrent()
            assertEquals(listOf("archive"), transport.sent.map { it.action })
            assertEquals(IntentPhase.Done, queue.byId(id)!!.state)
            assertEquals(mapOf("m1" to "archive1"), queue.byId(id)!!.filedFolderIds)
        }

    @Test
    fun actionsOnOneMessageArriveInTheOrderTheyWereTaken() =
        runTest {
            val queue = queue()
            transport.onMessage = {
                delay(50)
                transport.ok(it)
            }
            queue.enqueue(request(TriageAction.MarkRead, "m1"))
            queue.enqueue(request(TriageAction.Archive, "m1"))
            queue.enqueue(request(TriageAction.Flag, "m2"))
            advanceTimeBy(1_000)
            assertEquals(listOf("mark_read", "archive", "flag"), transport.sent.map { it.action })
        }

    @Test
    fun anAccountDrainsOneRequestAtATimeAndAccountsAreIndependent() =
        runTest {
            val queue = queue()
            transport.onMessage = {
                delay(100)
                transport.ok(it)
            }
            queue.enqueue(request(TriageAction.MarkRead, "m1"))
            queue.enqueue(request(TriageAction.MarkRead, "m2"))
            runCurrent()
            assertEquals("one request out for the account", 1, transport.inFlight)
            queue.enqueue(request(TriageAction.MarkRead, "x1", account = "a2"))
            runCurrent()
            assertEquals("the other account does not wait", 2, transport.inFlight)
            advanceTimeBy(1_000)
            assertEquals(3, transport.sent.size)
        }

    @Test
    fun aRetryIsTheSameRequestToTheServer() =
        runTest {
            val queue = queue()
            var calls = 0
            transport.onMessage = { if (calls++ < 2) throw ApiError.Transport("no route") else transport.ok(it) }
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            advanceTimeBy(10_000)
            assertEquals(3, transport.sent.size)
            assertEquals(
                "every attempt carries the intent id as its idempotency key",
                setOf(id),
                transport.sent
                    .map {
                        it.key
                    }.toSet(),
            )
            assertEquals(IntentPhase.Done, queue.byId(id)!!.state)
        }

    @Test
    fun backoffDoublesAndIsCapped() =
        runTest {
            val timing = ActionQueue.Timing(maxAttempts = 12, backoffBaseMs = 1_000, backoffCapMs = 8_000)
            val queue = queue(timing = timing)
            val times = mutableListOf<Long>()
            transport.onMessage =
                {
                    times += testScheduler.currentTime
                    throw ApiError.Http(500, "Internal Server Error")
                }
            queue.enqueue(request(TriageAction.MarkRead, "m1"))
            advanceTimeBy(200_000)
            val gaps = times.zipWithNext { a, b -> b - a }
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L), gaps.take(6))
        }

    @Test
    fun anActionThatKeepsFailingFailsVisiblyAndRetryStartsItAgain() =
        runTest {
            val queue = queue(timing = ActionQueue.Timing(maxAttempts = 3, backoffBaseMs = 10))
            transport.onMessage = { throw ApiError.Http(502, "Bad Gateway") }
            val notices = mutableListOf<QueueNotice>()
            backgroundScope.launchCollect(queue.notices) { notices += it }
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            advanceTimeBy(5_000)
            val failed = queue.byId(id)!!
            assertEquals(IntentPhase.Failed, failed.state)
            assertEquals(3, failed.attempts)
            assertEquals(IntentRowState.Failed, queue.rowState("m1"))
            assertTrue(notices.single() is QueueNotice.Failure)
            assertTrue(notices.single().message.startsWith("Could not archive"))

            transport.onMessage = { transport.ok(it) }
            queue.retry(listOf(id))
            advanceTimeBy(1_000)
            assertEquals(IntentPhase.Done, queue.byId(id)!!.state)
        }

    @Test
    fun aRefusalFailsAtOnceAndAGoneMessageSimplyLeaves() =
        runTest {
            val queue = queue()
            transport.onMessage =
                {
                    if (it.ids.first() ==
                        "gone"
                    ) {
                        throw ApiError.Detail("Not Found", 404)
                    } else {
                        throw ApiError.Detail("nope", 400)
                    }
                }
            val refused = queue.enqueue(request(TriageAction.Archive, "m1"))
            val gone = queue.enqueue(request(TriageAction.Archive, "gone"))
            advanceTimeBy(1_000)
            assertEquals(IntentPhase.Failed, queue.byId(refused)!!.state)
            assertEquals(1, queue.byId(refused)!!.attempts)
            assertNull(queue.byId(gone))
        }

    @Test
    fun aRefusedCredentialHoldsEverythingUnfailedUntilTheRecheckOrAResume() =
        runTest {
            val queue = queue()
            var refuse = true
            transport.onMessage = { if (refuse) throw ApiError.Http(403, "Forbidden") else transport.ok(it) }
            val first = queue.enqueue(request(TriageAction.MarkRead, "m1"))
            val second = queue.enqueue(request(TriageAction.MarkRead, "m2"))
            advanceTimeBy(1_000)
            assertEquals("held, not failed, and the second never tried", 1, transport.sent.size)
            assertEquals(IntentPhase.Pending, queue.byId(first)!!.state)
            assertEquals(0, queue.byId(first)!!.attempts)
            assertNotEquals(null, queue.state.value.heldUntilMs)

            refuse = false
            queue.resume()
            advanceTimeBy(1_000)
            assertEquals(IntentPhase.Done, queue.byId(first)!!.state)
            assertEquals(IntentPhase.Done, queue.byId(second)!!.state)
        }

    @Test
    fun aHeldQueueTriesAgainOnItsOwnAfterTheRecheck() =
        runTest {
            val queue = queue(timing = ActionQueue.Timing(holdRecheckMs = 30_000))
            var refuse = true
            transport.onMessage = { if (refuse) throw ApiError.Http(401, "Unauthorized") else transport.ok(it) }
            val id = queue.enqueue(request(TriageAction.MarkRead, "m1"))
            advanceTimeBy(10_000)
            refuse = false
            advanceTimeBy(30_000)
            assertEquals(IntentPhase.Done, queue.byId(id)!!.state)
        }

    @Test
    fun nothingLeavesWhileOfflineAndEverythingGoesWhenBack() =
        runTest {
            online.value = false
            val queue = queue()
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            advanceTimeBy(60_000)
            assertTrue(transport.sent.isEmpty())
            assertEquals(IntentRowState.Waiting, queue.rowState("m1"))
            assertEquals(IntentPhase.Pending, queue.byId(id)!!.state)

            online.value = true
            runCurrent()
            assertEquals(1, transport.sent.size)
            assertEquals(IntentPhase.Done, queue.byId(id)!!.state)
        }

    @Test
    fun aBusyServerIsAskedAgainWithoutCountingTheAttempt() =
        runTest {
            val queue = queue(timing = ActionQueue.Timing(maxAttempts = 2, busyRetryMs = 1_000))
            var calls = 0
            transport.onMessage = { if (calls++ < 5) throw ApiError.Detail("applying", 503) else transport.ok(it) }
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            advanceTimeBy(20_000)
            assertEquals(IntentPhase.Done, queue.byId(id)!!.state)
            assertEquals(6, transport.sent.size)
        }

    @Test
    fun anAttemptThatMayHaveLandedIsCheckedAgainstTheMessageBeforeBeingSentAgain() =
        runTest {
            val queue = queue()
            transport.onMessage = { throw ApiError.Transport("the answer never came") }
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            runCurrent()
            assertTrue(queue.byId(id)!!.mayHaveLanded)
            // The server had applied it: the message is in the archive.
            transport.states["m1"] = MessageState("archive1")
            advanceTimeBy(2_000)
            assertEquals("no second request", 1, transport.sent.size)
            assertEquals(IntentPhase.Done, queue.byId(id)!!.state)
            assertEquals(1, transport.stateReads)
        }

    @Test
    fun aMessageNotWhereTheActionPutItIsSentAgainWithTheSameKey() =
        runTest {
            val queue = queue()
            var calls = 0
            transport.onMessage = { if (calls++ == 0) throw ApiError.Transport("lost") else transport.ok(it) }
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            transport.states["m1"] = MessageState("inbox1")
            advanceTimeBy(2_000)
            assertEquals(2, transport.sent.size)
            assertEquals(setOf(id), transport.sent.map { it.key }.toSet())
        }

    @Test
    fun aMoveIsGuardedByTheFolderTheMessageWasSeenIn() =
        runTest {
            val queue = queue()
            queue.enqueue(request(TriageAction.Archive, "m1", origin = "inbox1"))
            queue.enqueue(request(TriageAction.MarkRead, "m2", origin = "inbox1"))
            runCurrent()
            assertEquals(mapOf("m1" to "inbox1"), transport.sent[0].expected)
            assertNull("a read follows the message anywhere", transport.sent[1].expected)
        }

    @Test
    fun aGuardedActionThatFindsTheMessageElsewhereWritesNothingAndSaysSo() =
        runTest {
            val queue = queue()
            val notices = mutableListOf<QueueNotice>()
            backgroundScope.launchCollect(queue.notices) { notices += it }
            transport.onMessage =
                {
                    com.frederikberg.mailverdict.contract.MessageActionResponse(
                        it.ids.first(),
                        it.action,
                        success = true,
                        applied = false,
                    )
                }
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            advanceTimeBy(1_000)
            assertNull(queue.byId(id))
            assertEquals("Did not archive: the message had already moved", notices.single().message)
        }

    @Test
    fun aBulkActionOnConversationRowsExpandsThemUpToWhatWasSeen() =
        runTest {
            val queue = queue()
            val request =
                request(
                    TriageAction.Archive,
                    "m1",
                    "m2",
                    delivery = Delivery.Bulk(expandThreads = true),
                ).copy(seenThrough = "2026-11-14T08:00:00+00:00")
            queue.enqueue(request)
            runCurrent()
            val sent = transport.sent.single()
            assertEquals("bulk", sent.kind)
            assertTrue(sent.expandThreads)
            assertEquals("2026-11-14T08:00:00+00:00", sent.seenThrough)
            assertEquals(mapOf("m1" to "inbox1", "m2" to "inbox1"), sent.expected)
        }

    @Test
    fun aSkippedBulkAnswerIsNotApplied() =
        runTest {
            val queue = queue()
            transport.onBulk = { BulkActionResponse(it.action, true, 0, skippedIds = it.ids) }
            val id = queue.enqueue(request(TriageAction.Archive, "m1", "m2"))
            advanceTimeBy(1_000)
            assertNull(queue.byId(id))
        }

    // Undo

    @Test
    fun anActionThatNeverLeftIsDroppedByUndo() =
        runTest {
            online.value = false
            val queue = queue()
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            runCurrent()
            queue.undo(listOf(id))
            online.value = true
            advanceTimeBy(5_000)
            assertTrue(transport.sent.isEmpty())
            assertTrue(
                queue.state.value.intents
                    .isEmpty(),
            )
        }

    @Test
    fun anUndoneMoveGoesBackToItsOriginFolderExpectingWhereTheServerFiledIt() =
        runTest {
            val queue = queue()
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            runCurrent()
            queue.undo(listOf(id))
            runCurrent()
            val back = transport.sent.last()
            assertEquals("move", back.action)
            assertEquals("inbox1", back.target)
            assertEquals("guarded to where it was filed", mapOf("m1" to "archive1"), back.expected)
            assertEquals(
                id,
                queue.state.value.intents
                    .last()
                    .undoes,
            )
        }

    @Test
    fun anUndoThatDoesNotKnowWhereTheMessageWasFiledExpectsItWhereTheActionFilesMessages() =
        runTest {
            val queue = queue()
            transport.onMessage = { throw ApiError.Transport("lost") }
            val id = queue.enqueue(request(TriageAction.Trash, "m1"))
            runCurrent()
            queue.undo(listOf(id))
            transport.onMessage = { transport.ok(it) }
            transport.states["m1"] = MessageState("inbox1")
            advanceTimeBy(5_000)
            val back = transport.sent.last()
            assertEquals("move", back.action)
            assertEquals(mapOf("m1" to "trash1"), back.expected)
        }

    @Test
    fun readAndStarAreUndoneByFlippingThemBack() =
        runTest {
            val queue = queue()
            val id = queue.enqueue(request(TriageAction.Flag, "m1"))
            runCurrent()
            queue.undo(listOf(id))
            runCurrent()
            assertEquals(listOf("flag", "unflag"), transport.sent.map { it.action })
        }

    @Test
    fun anActionUndoneWhileItsRequestIsOutStopsApplyingAndIsReversedWhenItSettles() =
        runTest {
            val queue = queue()
            transport.onMessage = {
                delay(1_000)
                transport.ok(it)
            }
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            runCurrent()
            queue.undo(listOf(id))
            assertFalse(IntentProjection.applies(queue.byId(id)!!, 0))
            advanceTimeBy(5_000)
            assertEquals(listOf("archive", "move"), transport.sent.map { it.action })
        }

    @Test
    fun aMoveIntoAFolderOtherThanItsOriginIsReversedToTheOrigin() =
        runTest {
            val queue = queue()
            val id = queue.enqueue(request(TriageAction.Move, "m1", target = "projects1"))
            runCurrent()
            queue.undo(listOf(id))
            runCurrent()
            assertEquals("inbox1", transport.sent.last().target)
        }

    @Test
    fun anExpandedConversationIsMovedBackMessageByMessage() =
        runTest {
            val queue = queue()
            transport.onBulk = {
                BulkActionResponse(
                    it.action,
                    true,
                    2,
                    sources = listOf(BulkActionSource("m1", "inbox1"), BulkActionSource("m1b", "other")),
                    targetFolderId = "archive1",
                )
            }
            val id = queue.enqueue(request(TriageAction.Archive, "m1", "m1b", delivery = Delivery.Bulk(true)))
            runCurrent()
            queue.undo(listOf(id))
            runCurrent()
            val backs = transport.sent.drop(1)
            assertEquals(setOf("inbox1", "other"), backs.map { it.target }.toSet())
            assertEquals(2, backs.size)
        }

    // Delete

    @Test
    fun deleteSendsTrashGuardedByTheFolderAndCanBeUndone() =
        runTest {
            val queue = queue()
            val id = queue.enqueue(request(TriageAction.Trash, "m1"))
            runCurrent()
            val sent = transport.sent.single()
            assertEquals("trash", sent.action)
            assertEquals(mapOf("m1" to "inbox1"), sent.expected)
            assertFalse(sent.confirm)
            queue.undo(listOf(id))
            runCurrent()
            assertEquals("move", transport.sent.last().action)
        }

    @Test
    fun deleteForeverIsConfirmedNeverReversedAndNotOfferedAnUndo() =
        runTest {
            val queue = queue()
            val id = queue.enqueue(request(TriageAction.Expunge, "m1", origin = "trash1").copy(confirm = true))
            runCurrent()
            assertTrue(transport.sent.single().confirm)
            queue.undo(listOf(id))
            runCurrent()
            assertEquals("nothing to reverse", 1, transport.sent.size)
            assertNull(TriageRequests.undoNotice(TriageAction.Expunge, 1))
        }

    @Test
    fun aDeleteForeverThatMayHaveLandedIsSentAgainWithItsKeyNeverGuessedDone() =
        runTest {
            val queue = queue()
            var calls = 0
            transport.onMessage = { if (calls++ == 0) throw ApiError.Transport("lost") else transport.ok(it) }
            val id = queue.enqueue(request(TriageAction.Expunge, "m1", origin = "trash1").copy(confirm = true))
            transport.states["m1"] = null
            advanceTimeBy(5_000)
            assertEquals(
                "the message looks gone, but only the server's idempotency answer settles it",
                2,
                transport.sent.size,
            )
            assertEquals(setOf(id), transport.sent.map { it.key }.toSet())
        }

    // Persistence

    @Test
    fun unsentActionsSurviveARestartAndAreSentByTheNextQueue() =
        runTest {
            online.value = false
            val first = queue()
            val id = first.enqueue(request(TriageAction.Archive, "m1"))
            runCurrent()
            first.stop()
            assertEquals(listOf(id), persistence.stored.map { it.id })

            online.value = true
            val second = queue()
            runCurrent()
            assertEquals(IntentPhase.Done, second.byId(id)!!.state)
            assertEquals(id, transport.sent.single().key)
        }

    @Test
    fun anAttemptThatWasOutWhenTheAppDiedIsCheckedBeforeItIsSentAgain() =
        runTest {
            val stored =
                MailIntent(
                    id = "x",
                    accountId = "a1",
                    action = TriageAction.Archive,
                    messageIds = listOf("m1"),
                    delivery = Delivery.Message,
                    originFolderIds = mapOf("m1" to "inbox1"),
                    createdAtMs = 0,
                    state = IntentPhase.Sending,
                    attempts = 1,
                )
            transport.states["m1"] = MessageState("archive1")
            val queue = queue(InMemoryIntentPersistence(listOf(stored)))
            runCurrent()
            assertTrue("it had landed: nothing is sent", transport.sent.isEmpty())
            assertEquals(IntentPhase.Done, queue.byId("x")!!.state)
        }

    @Test
    fun aDoneActionIsNotKeptAcrossARestart() =
        runTest {
            val done =
                MailIntent(
                    "d",
                    "a1",
                    TriageAction.MarkRead,
                    null,
                    listOf("m1"),
                    Delivery.Message,
                    createdAtMs = 0,
                    state = IntentPhase.Done,
                )
            val queue = queue(InMemoryIntentPersistence(listOf(done)))
            assertTrue(
                queue.state.value.intents
                    .isEmpty(),
            )
        }

    // Echo

    @Test
    fun anEventForAnActionTheDeviceJustSentIsItsEchoAndAnotherClientsChangeIsNot() =
        runTest {
            val queue = queue()
            transport.onMessage = {
                delay(200)
                transport.ok(it)
            }
            queue.enqueue(request(TriageAction.Archive, "m1"))
            advanceTimeBy(300)
            val now = testScheduler.currentTime
            assertTrue(queue.isSettledEcho("m1", "a1", now))
            assertFalse(queue.isSettledEcho("m2", "a1", now))
            assertFalse("before the request left", queue.isSettledEcho("m1", "a1", -1))
            assertFalse("long after the answer", queue.isSettledEcho("m1", "a1", now + 10_000))
        }

    @Test
    fun aSettledActionLeavesTheQueueAfterItsRetentionAndTellsTheObserversFirst() =
        runTest {
            val queue = queue(timing = ActionQueue.Timing(doneRetentionMs = 5_000))
            val retired = mutableListOf<String>()
            queue.addObserver(
                object : IntentObserver {
                    override fun intentsWillRetire(intents: List<MailIntent>) {
                        retired += intents.map { it.id }
                    }
                },
            )
            val id = queue.enqueue(request(TriageAction.Archive, "m1"))
            runCurrent()
            assertTrue(queue.byId(id) != null)
            advanceTimeBy(6_000)
            assertEquals(listOf(id), retired)
            assertNull(queue.byId(id))
        }

    private fun <T> kotlinx.coroutines.CoroutineScope.launchCollect(
        flow: kotlinx.coroutines.flow.Flow<T>,
        block: (T) -> Unit,
    ) {
        kotlinx.coroutines.CoroutineScope(coroutineContext).let { s -> s.launch { flow.collect { block(it) } } }
    }
}
