package com.frederikberg.mailverdict.kit.maillist

import com.frederikberg.mailverdict.kit.cache.FileOfflineCache
import com.frederikberg.mailverdict.kit.cache.OfflineListKey
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MailListStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val folder = ListScope.Folder("a1", "f1")
    private val dir = Files.createTempDirectory("maillist-store").toFile()

    @After
    fun stop() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun store(
        backend: FakeMailListBackend,
        around: String? = null,
        cache: FileOfflineCache = FileOfflineCache(dir),
    ) = MailListStore(folder, backend, scope, cache = cache, aroundMessageId = around)

    private fun <T> eventually(block: suspend CoroutineScope.() -> T): T =
        runBlocking { withTimeout(10_000) { block() } }

    private fun MailListStore.ids() = state.value.rows.map { it.id }

    private suspend fun until(condition: suspend () -> Boolean) {
        while (!condition()) delay(5)
    }

    @Test
    fun openingLoadsTheNewestPageAndTheFolderContext() =
        eventually {
            val backend = FakeMailListBackend(rows(120 downTo 1))
            val store = store(backend)
            store.start()
            val state = store.state.value
            assertEquals(ListPhase.Loaded, state.phase)
            assertEquals(rows(120 downTo 71).map { it.id }, store.ids())
            assertTrue(state.hasOlder)
            assertFalse(state.hasNewer)
            assertEquals(120, state.context.totalCount)
            assertEquals("120 messages", state.countLabel)
            assertEquals(MailListWindow.PAGE_SIZE, backend.calls.first().limit)
        }

    @Test
    fun startingTwiceReadsOnce() =
        eventually {
            val backend = FakeMailListBackend(rows(10 downTo 1))
            val store = store(backend)
            store.start()
            store.start()
            assertEquals(1, backend.calls.size)
        }

    @Test
    fun olderPagesAppendWithoutDuplicatesAndStopAtTheEnd() =
        eventually {
            val backend = FakeMailListBackend(rows(120 downTo 1))
            val store = store(backend)
            store.start()
            store.loadOlder()
            store.loadOlder()
            assertEquals(rows(120 downTo 1).map { it.id }, store.ids())
            assertFalse(store.state.value.hasOlder)
            val reads = backend.calls.size
            store.loadOlder()
            assertEquals("nothing left to page toward", reads, backend.calls.size)
        }

    @Test
    fun aRefreshSplicesArrivalsOnTopAndNeverGrowsTheWindowDownward() =
        eventually {
            val backend = FakeMailListBackend(rows(120 downTo 1))
            val store = store(backend)
            store.start()
            backend.messages = listOf(message(122), message(121)) + backend.messages
            store.refresh()
            assertEquals(listOf(id(122), id(121)) + rows(120 downTo 71).map { it.id }, store.ids())
            assertTrue(store.state.value.hasOlder)
        }

    @Test
    fun aRowDeletedOnTheServerLeavesTheListOnRefresh() =
        eventually {
            val backend = FakeMailListBackend(rows(60 downTo 1))
            val store = store(backend)
            store.start()
            backend.messages = backend.messages.filter { it.id != id(55) }
            store.refresh()
            assertFalse(id(55) in store.ids())
            assertEquals(49, store.ids().size)
        }

    @Test
    fun concurrentRefreshRequestsCollapseToOneRunningAndOneQueued() =
        eventually {
            val backend = FakeMailListBackend(rows(20 downTo 1))
            val store = store(backend)
            store.start()
            val before = backend.calls.size
            val gate = CompletableDeferred<Unit>()
            backend.gate = gate
            val first = async { store.refresh() }
            until { backend.calls.size > before }
            // The read is in flight now: everything asked from here on shares one more read.
            val running = listOf(first) + List(7) { async { store.refresh() } }
            delay(100)
            gate.complete(Unit)
            running.awaitAll()
            assertEquals("one read in flight, one more queued behind it", 2, backend.calls.size - before)
        }

    @Test
    fun aListOpenedAroundAMessageNeverTakesArrivalsAboveItAndCountsThem() =
        eventually {
            val backend = FakeMailListBackend(rows(200 downTo 1))
            val store = store(backend, around = id(100))
            store.start()
            val state = store.state.value
            assertTrue(state.hasNewer)
            assertEquals(ListLanding.RevealInUpperThird(id(100)), store.consumeLanding())
            assertNull("a landing is handed over once", store.consumeLanding())

            backend.messages = listOf(message(201)) + backend.messages
            store.apply(listOf(LiveInvalidation.MailNew("a1", "f1", id(201))))
            until { backend.calls.size > 1 }
            store.refresh()
            assertFalse(id(201) in store.ids())
            assertEquals(1, store.state.value.pendingArrivals)
            assertTrue(store.state.value.hasNewer)
        }

    @Test
    fun pagingUpToTheNewestEdgeClosesTheGapAndTheArrivalsInIt() =
        eventually {
            val backend = FakeMailListBackend(rows(200 downTo 1))
            val store = store(backend, around = id(120))
            store.start()
            backend.messages = listOf(message(201)) + backend.messages
            store.apply(listOf(LiveInvalidation.MailNew("a1", "f1", id(201))))
            until { store.state.value.pendingArrivals == 1 }
            store.refresh()
            while (store.state.value.hasNewer) store.loadNewer()
            assertEquals(id(201), store.ids().first())
            assertEquals(0, store.state.value.pendingArrivals)
            assertEquals("the rows are one contiguous run", store.ids(), store.ids().sortedDescending())
        }

    @Test
    fun aTargetThatIsNoMemberOfTheListFallsBackToTheNewestEdge() =
        eventually {
            val backend = FakeMailListBackend(rows(60 downTo 1))
            val store = store(backend, around = "00000000-0000-4000-8000-999999999999")
            store.start()
            assertEquals(ListPhase.Loaded, store.state.value.phase)
            assertEquals(id(60), store.ids().first())
            assertNull(store.consumeLanding())
        }

    @Test
    fun theUnreadFilterIsANewListThatChangesIdentityOnlyWhenItsPageLands() =
        eventually {
            val rows = (1..80).map { message(it, seen = it % 2 == 0) }.reversed()
            val backend = FakeMailListBackend(rows)
            val store = store(backend)
            store.start()
            val first = store.state.value.identity

            val gate = CompletableDeferred<Unit>()
            backend.gate = gate
            val change = async { store.setUnreadOnly(true) }
            until { backend.calls.size > 1 }
            assertTrue("the toggle shows at once", store.state.value.unreadOnly)
            assertEquals("the rows and the identity stay until the new page lands", first, store.state.value.identity)
            assertEquals(50, store.ids().size)
            gate.complete(Unit)
            change.await()
            assertTrue(store.state.value.identity != first)
            assertTrue(
                store.state.value.rows
                    .all { it.isUnread },
            )
            assertTrue(backend.calls.last().unreadOnly)
        }

    @Test
    fun aRowOpenedFromTheUnreadFilterShowsReadAndStaysThroughARefresh() =
        eventually {
            val backend =
                FakeMailListBackend(
                    listOf(message(3, seen = false), message(2, seen = false), message(1, seen = false)),
                )
            val store = store(backend)
            store.start()
            store.setUnreadOnly(true)
            store.didOpen(id(2))
            assertFalse(
                store.state.value.rows
                    .first { it.id == id(2) }
                    .isUnread,
            )
            // The server now answers a list without it; the reader keeps seeing the row until the filter changes.
            backend.messages = backend.messages.map { if (it.id == id(2)) it.copy(isSeen = true) else it }
            store.refresh()
            assertTrue(id(2) in store.ids())
            assertFalse(
                store.state.value.rows
                    .first { it.id == id(2) }
                    .isUnread,
            )
            store.setUnreadOnly(false)
            assertTrue(id(2) in store.ids())
            store.setUnreadOnly(true)
            assertFalse("a new unread list drops it", id(2) in store.ids())
        }

    @Test
    fun anUnreachableServerShowsTheSavedPageAndTheFirstAnsweredReadReplacesIt() =
        eventually {
            val backend = FakeMailListBackend(rows(120 downTo 1))
            val cache = FileOfflineCache(dir)
            val online = store(backend, cache = cache)
            online.start()
            until { cache.list(OfflineListKey(folder, true, false)) != null }

            backend.failure = ApiError.Transport("no route")
            val offline = store(backend, cache = cache)
            offline.start()
            val state = offline.state.value
            assertEquals(ListPhase.Loaded, state.phase)
            assertNotNull(state.savedCopyAtMs)
            assertEquals(online.ids().take(100), offline.ids())

            backend.failure = null
            // Much arrived since the page was saved: the saved rows are replaced, never kept under a gap.
            backend.messages = rows(300 downTo 1)
            offline.refresh()
            assertNull(offline.state.value.savedCopyAtMs)
            assertEquals(rows(300 downTo 201).map { it.id }, offline.ids())
        }

    @Test
    fun withNothingSavedAnUnreachableServerIsAFailureTheReaderCanRetry() =
        eventually {
            val backend = FakeMailListBackend(rows(5 downTo 1))
            backend.failure = ApiError.Transport("no route")
            val store = store(backend)
            store.start()
            assertTrue(store.state.value.phase is ListPhase.Failed)
            backend.failure = null
            store.retry()
            assertEquals(ListPhase.Loaded, store.state.value.phase)
            assertEquals(5, store.ids().size)
        }

    @Test
    fun aFailedRefreshKeepsTheRowsOnScreen() =
        eventually {
            val backend = FakeMailListBackend(rows(5 downTo 1))
            val store = store(backend)
            store.start()
            backend.failure = ApiError.Transport("no route")
            store.refresh()
            assertEquals(5, store.ids().size)
            assertEquals(ListPhase.Loaded, store.state.value.phase)
        }

    @Test
    fun liveEventsOfOtherFoldersDoNotReadTheList() =
        eventually {
            val backend = FakeMailListBackend(rows(5 downTo 1))
            val store = store(backend)
            store.start()
            val reads = backend.calls.size
            store.apply(listOf(LiveInvalidation.MailNew("a1", "other", id(9))))
            store.apply(listOf(LiveInvalidation.MailUpdated("a1", "other", "other", id(9), listOf("is_seen"))))
            delay(200)
            assertEquals(reads, backend.calls.size)
            store.apply(listOf(LiveInvalidation.MailNew("a1", "f1", id(9))))
            until { backend.calls.size > reads }
        }

    @Test
    fun aMoveOutOfThisFolderRefreshesItThroughTheOldFolderId() =
        eventually {
            val backend = FakeMailListBackend(rows(5 downTo 1))
            val store = store(backend)
            store.start()
            val reads = backend.calls.size
            store.apply(
                listOf(LiveInvalidation.MailUpdated("a1", "elsewhere", "f1", "unknown-id", listOf("folder_id"))),
            )
            until { backend.calls.size > reads }
        }

    @Test
    fun selectionStartsOnARowTogglesAndEndsClean() =
        eventually {
            val backend = FakeMailListBackend(rows(5 downTo 1))
            val store = store(backend)
            store.start()
            store.toggleSelection(id(3))
            assertFalse("toggling needs the mode", store.state.value.isSelecting)
            store.startSelecting(id(3))
            store.toggleSelection(id(2))
            store.toggleSelection(id(3))
            assertEquals(setOf(id(2)), store.state.value.selectedIds)
            store.selectLoaded()
            assertEquals(5, store.state.value.selectedIds.size)
            store.stopSelecting()
            assertFalse(store.state.value.isSelecting)
            assertTrue(
                store.state.value.selectedIds
                    .isEmpty(),
            )
        }

    @Test
    fun aNewListEndsSelection() =
        eventually {
            val backend = FakeMailListBackend(rows(5 downTo 1))
            val store = store(backend)
            store.start()
            store.startSelecting(id(3))
            store.setUnreadOnly(true)
            assertFalse(store.state.value.isSelecting)
        }

    @Test
    fun pullToRefreshAsksTheAccountToSyncThenReadsAgain() =
        eventually {
            val backend = FakeMailListBackend(rows(5 downTo 1))
            val store = store(backend)
            store.start()
            backend.messages = listOf(message(6)) + backend.messages
            store.pullToRefresh()
            assertEquals(listOf("a1"), backend.syncs.toList())
            assertEquals(id(6), store.ids().first())
        }

    @Test
    fun aPullToRefreshThatCannotReachTheServerSaysSo() =
        eventually {
            val backend = FakeMailListBackend(rows(5 downTo 1))
            val store = store(backend)
            store.start()
            backend.failure = ApiError.Transport("no route")
            store.pullToRefresh()
            assertTrue(
                store.state.value.notice!!
                    .startsWith("Could not refresh"),
            )
            store.dismissNotice()
            assertNull(store.state.value.notice)
        }

    @Test
    fun rowsInTheSentFolderShowRecipients() =
        eventually {
            val backend = FakeMailListBackend(listOf(message(1, folder = "f2")))
            val store = store(backend)
            store.start()
            until {
                store.state.value.context.folderRoles
                    .isNotEmpty()
            }
            assertEquals(
                "To: me",
                store.state.value.rows
                    .single()
                    .sender,
            )
        }

    @Test
    fun theViewportIsRememberedAcrossAVisitAndForgottenWhenTheListBecomesAnother() =
        eventually {
            val backend = FakeMailListBackend(rows(80 downTo 1))
            val store = store(backend)
            store.start()
            assertNull(store.savedViewport)
            store.saveViewport(ViewportAnchor(id(7), 12))
            store.refresh()
            assertEquals("a refresh is the same list", ViewportAnchor(id(7), 12), store.savedViewport)
            store.setUnreadOnly(true)
            assertNull("a different list starts at the top", store.savedViewport)
        }
}
