package com.frederikberg.mailverdict.maillist

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.height
import com.frederikberg.mailverdict.kit.live.OfflineNotice
import com.frederikberg.mailverdict.kit.maillist.ListIdentity
import com.frederikberg.mailverdict.kit.maillist.ListLanding
import com.frederikberg.mailverdict.kit.maillist.ListPhase
import com.frederikberg.mailverdict.kit.maillist.ListScope
import com.frederikberg.mailverdict.kit.maillist.MailListState
import com.frederikberg.mailverdict.kit.maillist.MailRowData
import com.frederikberg.mailverdict.kit.maillist.ViewportAnchor
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scroll properties of the mail list, measured on the real screen: nothing above the reader moves
 * while rows change underneath, every row has the one height it was given, a different list starts at
 * the top, and a visit to the reader is undone by identity. Each assertion names a number.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class MailListScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = ListScope.Folder("a1", "f1")
    private val first = ListIdentity(scope, threaded = true, unreadOnly = false)

    private fun id(n: Int) = "r" + n.toString().padStart(4, '0')

    /** Newest first: the highest number is the top row. */
    private fun rows(range: IntProgression) = range.map { row(it) }

    private fun row(
        n: Int,
        subject: String = "Subject $n",
        snippet: String = "Snippet $n of the message",
    ) = MailRowData(
        id = id(n),
        threadId = "t$n",
        accountId = "a1",
        folderId = "f1",
        sender = "Sender $n",
        date = "14:0${n % 10}",
        subject = subject,
        snippet = snippet,
        isUnread = n % 3 == 0,
        isFlagged = n % 7 == 0,
        isAnswered = false,
        isDraft = false,
        isSpam = false,
        hasAttachments = n % 5 == 0,
        isGlacier = false,
        threadCount = if (n % 4 == 0) 3 else 1,
        unreadInThread = 0,
    )

    private fun loaded(
        rows: List<MailRowData>,
        identity: ListIdentity = first,
        hasNewer: Boolean = false,
        hasOlder: Boolean = true,
    ) = MailListState(
        identity = identity,
        rows = rows,
        phase = ListPhase.Loaded,
        hasNewer = hasNewer,
        hasOlder = hasOlder,
    )

    private class Harness {
        var state by mutableStateOf<MailListState?>(null)
        lateinit var listState: LazyListState
        lateinit var coroutines: CoroutineScope
        var saved: ViewportAnchor? = null
        var reported: ViewportAnchor? = null
        var olderRequests = 0
        var newerRequests = 0
        var landed = 0
        var rowHeightPx = 0f
    }

    private fun show(
        harness: Harness,
        initial: MailListState,
        saved: ViewportAnchor? = null,
    ) {
        harness.state = initial
        harness.saved = saved
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                harness.coroutines = rememberCoroutineScope()
                val state = harness.state!!
                val listState = rememberMailListState(state.identity, state.rows, harness.saved)
                harness.listState = listState
                harness.rowHeightPx = with(LocalDensity.current) { rememberRowMetrics().itemHeight.toPx() }
                MailListScreen(
                    title = "Inbox",
                    state = state,
                    listState = listState,
                    offline = null as OfflineNotice?,
                    refreshing = false,
                    onBack = {},
                    onRefresh = {},
                    onOpenRow = {},
                    onLongPressRow = {},
                    onToggleSelection = {},
                    onUnreadOnly = {},
                    onThreaded = {},
                    onRetry = {},
                    onLoadOlder = { harness.olderRequests++ },
                    onLoadNewer = { harness.newerRequests++ },
                    onJumpToLatest = {},
                    onSelectLoaded = {},
                    onStopSelecting = {},
                    onLanded = { harness.landed++ },
                    onViewport = { harness.reported = it },
                    onDismissNotice = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun Harness.scrollTo(
        index: Int,
        offsetPx: Int = 0,
    ) {
        coroutines.launch { listState.scrollToItem(index, offsetPx) }
        compose.waitForIdle()
    }

    private fun Harness.place(): Pair<Any?, Int> {
        val info = listState.layoutInfo.visibleItemsInfo.first()
        return info.key to -info.offset
    }

    @Test
    fun prependingRowsAboveAParkedListLeavesTheTopRowWhereItWas() {
        val h = Harness()
        show(h, loaded(rows(100 downTo 1)))
        h.scrollTo(40, 25)
        val before = h.place()
        assertEquals("parked on row ${id(60)} 25px in", id(60) to 25, before)

        h.state = h.state!!.copy(rows = rows(130 downTo 101) + h.state!!.rows)
        compose.waitForIdle()

        val after = h.place()
        assertEquals(
            "30 rows prepended: top row ${before.first} offset ${before.second}px, now ${after.first} ${after.second}px",
            before,
            after,
        )
        assertEquals(70, h.listState.firstVisibleItemIndex)
    }

    @Test
    fun aRowRemovedAboveTheReaderLeavesTheTopRowWhereItWas() {
        val h = Harness()
        show(h, loaded(rows(100 downTo 1)))
        h.scrollTo(40, 25)
        val before = h.place()
        h.state = h.state!!.copy(rows = h.state!!.rows.filterNot { it.id == id(95) || it.id == id(80) })
        compose.waitForIdle()
        assertEquals(before, h.place())
        assertEquals(38, h.listState.firstVisibleItemIndex)
    }

    @Test
    fun aReaderAtTheTopOfTheNewestEdgeSeesArrivalsThere() {
        val h = Harness()
        show(h, loaded(rows(100 downTo 1)))
        assertEquals(0 to 0, h.listState.firstVisibleItemIndex to h.listState.firstVisibleItemScrollOffset)

        h.state = h.state!!.copy(rows = rows(103 downTo 101) + h.state!!.rows)
        compose.waitForIdle()

        assertEquals(id(103), h.place().first)
        assertEquals(
            "at the top, at offset 0",
            0 to 0,
            h.listState.firstVisibleItemIndex to h.listState.firstVisibleItemScrollOffset,
        )
    }

    @Test
    fun aReaderFurtherDownIsNotMovedAndIsToldHowManyRowsArrivedAbove() {
        val h = Harness()
        show(h, loaded(rows(100 downTo 1)))
        h.scrollTo(10, 5)
        val before = h.place()

        h.state = h.state!!.copy(rows = rows(103 downTo 101) + h.state!!.rows)
        compose.waitForIdle()

        assertEquals(before, h.place())
        compose.onNodeWithTag(MailListTags.NEW_CAPSULE).assertExists()

        h.scrollTo(0)
        compose.onNodeWithTag(MailListTags.NEW_CAPSULE).assertDoesNotExist()
    }

    @Test
    fun aWindowAwayFromTheNewestEdgeNeverJumpsToItsTop() {
        val h = Harness()
        show(h, loaded(rows(100 downTo 1), hasNewer = true))
        // At the top of a window with newer mail unloaded, rows landing above must not drag the reader.
        h.state = h.state!!.copy(rows = rows(103 downTo 101) + h.state!!.rows)
        compose.waitForIdle()
        assertEquals("the reader stays on row ${id(100)}", id(100), h.place().first)
    }

    @Test
    fun everyRowHasTheOneHeightItWasGivenWhateverItHoldsAndWhereverTheReaderIs() {
        val rows =
            rows(60 downTo 1).mapIndexed { index, r ->
                when (index % 6) {
                    0 -> r.copy(subject = "S".repeat(200), snippet = "word ".repeat(60))
                    1 -> r.copy(subject = "", snippet = "")
                    2 -> r.copy(snippet = "line\n".repeat(40).trim())
                    else -> r
                }
            }
        val h = Harness()
        show(h, loaded(rows))
        val heights = mutableMapOf<Any, Int>()

        fun record() =
            h.listState.layoutInfo.visibleItemsInfo
                .forEach { heights.putIfAbsent(it.key, it.size) }

        record()
        val before =
            h.listState.layoutInfo.visibleItemsInfo
                .associate { it.key to it.size }
        for (index in 0 until rows.size step 3) {
            h.scrollTo(index)
            record()
        }
        h.scrollTo(0)
        val after =
            h.listState.layoutInfo.visibleItemsInfo
                .associate { it.key to it.size }

        val distinct = heights.values.toSet()
        assertEquals(
            "${heights.size} rows laid out, heights seen: $distinct, expected ${h.rowHeightPx}px",
            setOf(h.rowHeightPx.toInt()),
            distinct,
        )
        assertTrue("the rows laid out more than the first screen: ${heights.size}", heights.size > 40)
        assertEquals("the same rows measure the same before and after reading through the list", before, after)
    }

    @Test
    fun aTwentyThousandCharacterRowIsNoTallerThanTheCap() {
        val huge = "x".repeat(20_000)
        val h = Harness()
        show(h, loaded(listOf(row(3, subject = huge, snippet = huge)) + rows(2 downTo 1)))
        val bounds = compose.onNodeWithTag(MailListTags.row(id(3))).getUnclippedBoundsInRoot()
        val rowHeightDp = with(compose.density) { h.rowHeightPx.toDp() }
        assertEquals("row measured ${bounds.height}, cap $rowHeightDp", rowHeightDp.value, bounds.height.value, 0.01f)
    }

    @Test
    fun aDifferentListStartsAtTheTopAndNeverInheritsTheOffsetOfTheOneItReplaced() {
        val h = Harness()
        show(h, loaded(rows(100 downTo 1)))
        h.scrollTo(40, 25)
        assertEquals(40, h.listState.firstVisibleItemIndex)

        val unread = first.copy(unreadOnly = true, generation = 1)
        h.state = loaded(rows(100 downTo 1).filter { it.isUnread }, identity = unread)
        compose.waitForIdle()

        assertEquals(
            "a new list: index ${h.listState.firstVisibleItemIndex} offset ${h.listState.firstVisibleItemScrollOffset}px",
            0 to 0,
            h.listState.firstVisibleItemIndex to h.listState.firstVisibleItemScrollOffset,
        )
    }

    @Test
    fun returningToAListRestoresTheRowByIdentityEvenWhenRowsLandedAboveItMeanwhile() {
        val h = Harness()
        val rows = rows(130 downTo 101) + rows(100 downTo 1)
        show(h, loaded(rows), saved = ViewportAnchor(id(60), 25))
        val restored = h.place()
        assertEquals(
            "restored onto ${id(60)} 25px in, at index ${h.listState.firstVisibleItemIndex}",
            id(60) to 25,
            restored,
        )
        assertEquals(70, h.listState.firstVisibleItemIndex)
    }

    @Test
    fun aSavedRowThatLeftTheListFallsBackToTheTop() {
        val h = Harness()
        show(h, loaded(rows(100 downTo 1)), saved = ViewportAnchor("gone", 25))
        assertEquals(0 to 0, h.listState.firstVisibleItemIndex to h.listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun theViewportIsReportedByRowIdentityForTheNextVisit() {
        val h = Harness()
        show(h, loaded(rows(100 downTo 1)))
        h.scrollTo(40, 25)
        compose.waitForIdle()
        assertEquals(ViewportAnchor(id(60), 25), h.reported)
    }

    @Test
    fun pagesAreRequestedTwoScreensBeforeTheEdgeAndNeverWhileFarFromIt() {
        val h = Harness()
        show(h, loaded(rows(200 downTo 1)))
        h.scrollTo(80)
        assertEquals(
            "far from either end: ${h.olderRequests} older, ${h.newerRequests} newer",
            0 to 0,
            h.olderRequests to h.newerRequests,
        )
        // 200 rows, about nine on a screen: the last visible index is 188 at the point the margin starts to bite.
        h.scrollTo(185)
        assertTrue("older page asked for near the end: ${h.olderRequests}", h.olderRequests > 0)
        assertEquals(0, h.newerRequests)
    }

    @Test
    fun aWindowAwayFromTheNewestEdgeAsksForNewerRowsBeforeTheTopIsReached() {
        val h = Harness()
        show(h, loaded(rows(200 downTo 1), hasNewer = true))
        h.scrollTo(15)
        assertTrue("newer page asked for ${h.newerRequests} times", h.newerRequests > 0)
    }

    @Test
    fun theLoadingFooterAddsNothingAboveTheReader() {
        val h = Harness()
        show(h, loaded(rows(100 downTo 1)))
        h.scrollTo(40, 25)
        val before = h.place()
        h.state = h.state!!.copy(isLoadingOlder = true, isLoadingNewer = true)
        compose.waitForIdle()
        assertEquals(before, h.place())
    }

    @Test
    fun aDeepLinkPutsItsRowAThirdOfTheWayDownOnce() {
        val h = Harness()
        show(h, loaded(rows(200 downTo 1), hasNewer = true).copy(landing = ListLanding.RevealInUpperThird(id(120))))
        compose.waitForIdle()
        val info =
            h.listState.layoutInfo.visibleItemsInfo
                .first { it.key == id(120) }
        val viewport = h.listState.layoutInfo.viewportSize.height
        assertTrue(
            "row ${id(120)} sits at ${info.offset}px of $viewport",
            kotlin.math.abs(info.offset - viewport / 3) <= 1,
        )
        assertEquals(1, h.landed)
    }

    @Test
    fun aRealSwipeScrollsTheList() {
        val h = Harness()
        show(h, loaded(rows(100 downTo 1)))
        compose.onNodeWithTag(MailListTags.LIST).performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("the list moved: index ${h.listState.firstVisibleItemIndex}", h.listState.firstVisibleItemIndex > 0)
    }
}
