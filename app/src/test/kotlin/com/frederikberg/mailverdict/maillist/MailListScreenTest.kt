package com.frederikberg.mailverdict.maillist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.fixtures.FixtureInterceptor
import com.frederikberg.mailverdict.kit.fixtures.Fixtures
import com.frederikberg.mailverdict.kit.live.OfflineNotice
import com.frederikberg.mailverdict.kit.live.Reachability
import com.frederikberg.mailverdict.kit.maillist.ApiMailListBackend
import com.frederikberg.mailverdict.kit.maillist.ListCursor
import com.frederikberg.mailverdict.kit.maillist.ListPhase
import com.frederikberg.mailverdict.kit.maillist.ListScope
import com.frederikberg.mailverdict.kit.maillist.MailListState
import com.frederikberg.mailverdict.kit.maillist.MailListStore
import com.frederikberg.mailverdict.kit.maillist.MailRowData
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class MailListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun fixtureState(
        scope: ListScope = ListScope.Folder(Fixtures.PRIVATE_ACCOUNT, "00000000-0000-4000-8000-0000000001f1"),
    ): MailListState {
        val client = OkHttpClient.Builder().addInterceptor(FixtureInterceptor(Fixtures.routes)).build()
        val api = ApiClient(RequestFactory("https://fixture.invalid") { AuthMode.None }, client)
        val store = MailListStore(scope, ApiMailListBackend(api), CoroutineScope(Dispatchers.Unconfined))
        runBlocking { store.start() }
        return store.state.value
    }

    private fun show(
        state: MailListState,
        offline: OfflineNotice? = null,
    ) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                val listState = rememberMailListState(state.identity, state.rows, null)
                MailListScreen(
                    title = "Inbox Private",
                    state = state,
                    listState = listState,
                    offline = offline,
                    refreshing = false,
                    onBack = { events += "back" },
                    onRefresh = { events += "refresh" },
                    onOpenRow = { events += "open:${it.subject}" },
                    onLongPressRow = { events += "long:${it.sender}" },
                    onToggleSelection = { events += "toggle:${it.sender}" },
                    onUnreadOnly = { events += "unread:$it" },
                    onThreaded = { events += "threaded:$it" },
                    onRetry = { events += "retry" },
                    onLoadOlder = {},
                    onLoadNewer = {},
                    onJumpToLatest = { events += "latest" },
                    onSelectLoaded = { events += "select-loaded" },
                    onStopSelecting = { events += "stop" },
                    onLanded = {},
                    onViewport = {},
                    onDismissNotice = {},
                )
            }
        }
    }

    private fun MailListState.row(prefix: String): MailRowData = rows.first { it.subject.startsWith(prefix) }

    @Test
    fun theFixtureInboxShowsEveryShapeOfRow() {
        val state = fixtureState()
        show(state)

        compose.onNodeWithText("Quarterly planning").assertIsDisplayed()
        compose.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        compose.onNodeWithText("(no subject)").assertIsDisplayed()
        compose.onNodeWithText("12 messages".let { "1204 messages" }, substring = true).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/maillist.png")
    }

    @Test
    fun tappingARowOpensItAndALongPressStartsSelecting() {
        val state = fixtureState()
        show(state)
        compose.onNodeWithTag(MailListTags.row(state.row("Quarterly").id)).performClick()
        compose.onNodeWithTag(MailListTags.row(state.row("Re: kernel").id)).performTouchInput { longClick() }
        assertEquals(listOf("open:Quarterly planning", "long:Linus"), events)
    }

    @Test
    fun whileSelectingATapTogglesAndTheBarCountsAndOffersWhatThePersonNeeds() {
        val state = fixtureState()
        val first = state.row("Quarterly").id
        val second = state.row("Re: kernel").id
        show(state.copy(isSelecting = true, selectedIds = setOf(first, second)))

        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithTag(MailListTags.row(first)).performClick()
        compose.onNodeWithTag(MailListTags.SELECT_LOADED).performClick()
        compose.onNodeWithTag(MailListTags.STOP_SELECTING).performClick()
        assertEquals(listOf("toggle:Ada Lovelace", "select-loaded", "stop"), events)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/maillist-selecting.png")
    }

    @Test
    fun theUnreadFilterAndTheGroupingToggleReportTheirNewValue() {
        val state = fixtureState()
        show(state)
        compose.onNodeWithTag(MailListTags.UNREAD_FILTER).performClick()
        compose.onNodeWithTag(MailListTags.MORE).performClick()
        compose.onNodeWithTag(MailListTags.THREADED).performClick()
        assertEquals(listOf("unread:true", "threaded:false"), events)
    }

    @Test
    fun anUnreadOnlyListSaysSoInItsSubtitle() {
        val state = fixtureState()
        show(state.copy(unreadOnly = true))
        compose.onNodeWithText("Unread").assertIsDisplayed()
    }

    @Test
    fun aSavedCopyAndAnUnreachableServerShowTheCapsule() {
        val state = fixtureState()
        val saved = System.currentTimeMillis() - 5 * 60_000
        show(
            state.copy(savedCopyAtMs = saved),
            OfflineNotice.make(Reachability.Offline, saved, System.currentTimeMillis()),
        )
        compose.onNodeWithText("Offline · Saved 5 min ago").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/maillist-offline.png")
    }

    @Test
    fun arrivalsInAWindowAwayFromTheNewestEdgeOfferAJump() {
        val state = fixtureState()
        show(state.copy(hasNewer = true, pendingArrivals = 4))
        compose.onNodeWithTag(MailListTags.ARRIVALS_CAPSULE).performClick()
        assertEquals(listOf("latest"), events)
    }

    @Test
    fun aListWithNoMessagesSaysWhich() {
        val state = fixtureState()
        show(state.copy(rows = emptyList(), unreadOnly = true))
        compose.onNodeWithTag(MailListTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithText("No unread messages here").assertIsDisplayed()
    }

    @Test
    fun aFailedFirstReadOffersARetry() {
        val state = fixtureState()
        show(state.copy(rows = emptyList(), phase = ListPhase.Failed("no route to the server")))
        compose.onNodeWithText("Could not load messages: no route to the server").assertIsDisplayed()
        compose.onNodeWithTag(MailListTags.RETRY).performClick()
        assertEquals(listOf("retry"), events)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/maillist-failed.png")
    }

    @Test
    fun theFirstReadShowsProgressNotAnEmptyList() {
        val state = fixtureState()
        show(state.copy(rows = emptyList(), phase = ListPhase.Loading))
        compose.onNodeWithTag(MailListTags.EMPTY).assertDoesNotExistCompat()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()

    @Test
    fun theUnifiedViewListsTheSameRows() {
        val state = fixtureState(ListScope.Unified("00000000-0000-4000-8000-0000000003a1", "Inboxes"))
        assertEquals(7, state.rows.size)
        assertEquals(ListCursor.Newest::class, ListCursor.Newest::class)
        show(state)
        compose.onNodeWithText("Quarterly planning").assertIsDisplayed()
    }
}
