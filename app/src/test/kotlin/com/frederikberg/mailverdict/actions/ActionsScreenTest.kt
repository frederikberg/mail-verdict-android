package com.frederikberg.mailverdict.actions

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.frederikberg.mailverdict.contract.FolderKind
import com.frederikberg.mailverdict.contract.FolderOrderItem
import com.frederikberg.mailverdict.contract.FolderOrderResponse
import com.frederikberg.mailverdict.kit.actions.ActionQueue
import com.frederikberg.mailverdict.kit.actions.ApiIntentTransport
import com.frederikberg.mailverdict.kit.actions.InMemoryIntentPersistence
import com.frederikberg.mailverdict.kit.actions.MessageActionSet
import com.frederikberg.mailverdict.kit.actions.MovePicker
import com.frederikberg.mailverdict.kit.actions.QueueNotice
import com.frederikberg.mailverdict.kit.actions.UiAction
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.fixtures.FixtureInterceptor
import com.frederikberg.mailverdict.kit.fixtures.Fixtures
import com.frederikberg.mailverdict.kit.maillist.ApiMailListBackend
import com.frederikberg.mailverdict.kit.maillist.ListScope
import com.frederikberg.mailverdict.kit.maillist.MailListState
import com.frederikberg.mailverdict.kit.maillist.MailListStore
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.maillist.ListActions
import com.frederikberg.mailverdict.maillist.MailListScreen
import com.frederikberg.mailverdict.maillist.MailListTags
import com.frederikberg.mailverdict.maillist.SelectionActions
import com.frederikberg.mailverdict.maillist.rememberMailListState
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi")
class ActionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val interceptor = FixtureInterceptor(Fixtures.routes)

    /** Holds every POST until the test lets it go, so the screen can be looked at while a request is out. */
    private val release = java.util.concurrent.CountDownLatch(1)
    private val api =
        ApiClient(
            RequestFactory("https://fixture.invalid", "Test phone") { AuthMode.None },
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    if (chain.request().method == "POST") release.await()
                    chain.proceed(chain.request())
                }.addInterceptor(interceptor)
                .build(),
        )
    private val scope = CoroutineScope(Dispatchers.Default)
    private val queue = ActionQueue(ApiIntentTransport(api), InMemoryIntentPersistence(), scope)
    private val notices = MutableSharedFlow<QueueNotice>(extraBufferCapacity = 4)
    private val swiped = mutableListOf<String>()
    private val undone = mutableListOf<List<String>>()
    private val retried = mutableListOf<String>()
    private val menu = mutableListOf<Pair<UiAction, Set<String>>>()

    private fun loadedStore(): MailListStore {
        val store =
            MailListStore(
                ListScope.Folder(Fixtures.PRIVATE_ACCOUNT, "00000000-0000-4000-8000-0000000001f1"),
                ApiMailListBackend(api),
                scope,
                actions = queue,
            )
        runBlocking { store.start() }
        return store
    }

    private fun show(
        store: MailListStore,
        state: () -> MailListState = { store.state.value },
        onSwipe: (com.frederikberg.mailverdict.kit.maillist.MailRowData, UiAction) -> Unit = { row, action ->
            swiped +=
                "${action.name}:${row.sender}"
        },
    ) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                val current by store.state.collectAsState()
                val queueState by queue.state.collectAsState()
                val listState = rememberMailListState(current.identity, current.rows, null)
                MailListScreen(
                    title = "Inbox",
                    state = current,
                    listState = listState,
                    offline = null,
                    refreshing = false,
                    onBack = {},
                    onRefresh = {},
                    onOpenRow = {},
                    onLongPressRow = { store.startSelecting(it.id) },
                    onToggleSelection = { store.toggleSelection(it.id) },
                    onUnreadOnly = {},
                    onThreaded = {},
                    onRetry = {},
                    onLoadOlder = {},
                    onLoadNewer = {},
                    onJumpToLatest = {},
                    onSelectLoaded = store::selectLoaded,
                    onStopSelecting = store::stopSelecting,
                    onLanded = {},
                    onViewport = {},
                    onDismissNotice = {},
                    selectionActions = {
                        val ids = current.selectedIds
                        SelectionActions(
                            MessageActionSet.groups(store.actionContext(ids)),
                            emptySet(),
                        ) {
                            menu +=
                                it to ids
                        }
                    },
                    actions =
                        ListActions(
                            notices = notices,
                            attention = queueState.summary,
                            swipeEnd = { MessageActionSet.swipeEnd(store.actionContext(setOf(it.id))) },
                            swipeStart = { MessageActionSet.swipeStart(store.actionContext(setOf(it.id))) },
                            onSwipe = onSwipe,
                            onUndo = { undone += it },
                            onRetryAction = { retried += it },
                        ),
                )
            }
        }
    }

    private fun rowId(store: MailListStore, subject: String) =
        store.state.value.rows
            .first {
                it.subject.startsWith(subject)
            }.id

    @Test
    fun aSwipeToTheRightArchivesAndToTheLeftDeletes() {
        val store = loadedStore()
        show(store)
        compose.onNodeWithTag(MailListTags.swipe(rowId(store, "Quarterly"))).performTouchInput { swipeRight() }
        compose.onNodeWithTag(MailListTags.swipe(rowId(store, "Re: kernel"))).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf("Archive:Ada Lovelace", "Delete:Linus"), swiped)
        // The row stays where it is until the queue's projection takes it away.
        compose.onNodeWithText("Quarterly planning").assertIsDisplayed()
    }

    @Test
    fun aSwipeThroughTheQueueTakesTheRowOffTheScreenAtOnceAndSendsTheRequest() {
        val store = loadedStore()
        show(store) { row, action -> store.perform(action, setOf(row.id)) }
        compose.onNodeWithTag(MailListTags.swipe(rowId(store, "Quarterly"))).performTouchInput { swipeRight() }
        compose.waitUntil(5_000) {
            store.state.value.rows
                .none { it.subject == "Quarterly planning" }
        }
        compose.onNodeWithText("Quarterly planning").assertDoesNotExistCompat()
        assertTrue("the request is still out", queue.state.value.hasOpen)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/maillist-after-swipe.png")
        release.countDown()
        runBlocking { queue.waitUntilIdle() }
        assertTrue(
            interceptor.requests.any {
                it ==
                    "POST /api/accounts/${Fixtures.PRIVATE_ACCOUNT}/messages/bulk-action"
            },
        )
    }

    @Test
    fun aSelectionOffersTheSameActionsAsEverywhereAndDeleteAtHand() {
        val store = loadedStore()
        show(store)
        val first = rowId(store, "Quarterly")
        val second = rowId(store, "Re: kernel")
        compose.runOnIdle {
            store.startSelecting(first)
            store.toggleSelection(second)
        }
        compose.onNodeWithTag(ActionTags.menu(UiAction.Delete)).performClick()
        compose.onNodeWithTag(MailListTags.SELECTION_MENU).performClick()
        compose.onNodeWithTag(ActionTags.menu(UiAction.Archive)).assertIsDisplayed()
        compose.onNodeWithText("Mark as read").assertIsDisplayed()
        compose.onNodeWithText("Move to…").assertIsDisplayed()
        compose.onNodeWithTag(ActionTags.menu(UiAction.Archive)).performClick()
        assertEquals(listOf(UiAction.Delete to setOf(first, second), UiAction.Archive to setOf(first, second)), menu)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/maillist-selection-menu.png")
    }

    @Test
    fun anUndoOfferShowsAndUndoReportsTheActionsToReverse() {
        val store = loadedStore()
        show(store)
        compose.runOnIdle { notices.tryEmit(QueueNotice.Undo("Archived", listOf("intent-1"))) }
        compose.onNodeWithText("Archived").assertIsDisplayed()
        compose.onNodeWithText(ACTION_UNDO).performClick()
        compose.waitForIdle()
        assertEquals(listOf(listOf("intent-1")), undone)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/maillist-undo.png")
    }

    @Test
    fun aFailureShowsWithRetryAndRetryReportsTheAction() {
        val store = loadedStore()
        show(store)
        compose.runOnIdle { notices.tryEmit(QueueNotice.Failure("Could not archive: no route", "intent-9")) }
        compose.onNodeWithText("Could not archive: no route").assertIsDisplayed()
        compose.onNodeWithText(ACTION_RETRY).performClick()
        compose.waitForIdle()
        assertEquals(listOf("intent-9"), retried)
    }

    @Test
    fun deleteForeverAsksBeforeItDoesAnythingAndCancelDoesNothing() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                ConfirmDeleteForeverDialog(count = 3, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
            }
        }
        compose.onNodeWithText("These 3 messages will be removed for good. This cannot be undone.").assertIsDisplayed()
        compose.onNodeWithTag(ActionTags.CANCEL_FOREVER).performClick()
        assertEquals(0 to 1, confirmed to dismissed)
        compose.onNodeWithTag(ActionTags.CONFIRM_FOREVER).performClick()
        assertEquals(1, confirmed)
    }

    private val order =
        FolderOrderResponse(
            listOf(
                FolderOrderItem("inbox", "INBOX", specialUse = "inbox"),
                FolderOrderItem("projects", "Projects"),
                FolderOrderItem("receipts", "Receipts"),
                FolderOrderItem("archive", "Archive", specialUse = "archive"),
                FolderOrderItem("glacier", "Glacier", kind = FolderKind.Glacier),
            ),
        )

    @Test
    fun theMovePickerListsFoldersRecentFirstWithoutTheCurrentOneOrTheGlacierAndFiltersAsTyped() {
        val targets = MovePicker.targets("a1", order)
        val picked = mutableListOf<String>()
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                MovePickerSheet(targets, excludingFolderIds = setOf("inbox"), recentIds = listOf("receipts"), onPick = {
                    picked +=
                        it.folderId
                }, onDismiss = {})
            }
        }
        compose.onNodeWithTag(ActionTags.target("receipts")).assertIsDisplayed()
        compose.onNodeWithTag(ActionTags.target("inbox")).assertDoesNotExistCompat()
        compose.onNodeWithTag(ActionTags.target("glacier")).assertDoesNotExistCompat()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/move-picker.png")
        compose.onNodeWithTag(ActionTags.MOVE_FIELD).performTextInput("proj")
        compose.onNodeWithTag(ActionTags.target("receipts")).assertDoesNotExistCompat()
        compose.onNodeWithTag(ActionTags.target("projects")).performClick()
        assertEquals(listOf("projects"), picked)
    }

    @Test
    fun theMovePickerWaitsForItsFolders() {
        compose.setContent {
            MailVerdictTheme(
                dynamicColor = false,
            ) { MovePickerSheet(null, emptySet(), emptyList(), onPick = {}, onDismiss = {}) }
        }
        compose.onNodeWithText("Loading folders…").assertIsDisplayed()
    }

    @Test
    fun anAttentionLineReplacesTheCountWhileActionsWait() {
        val store = loadedStore()
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                val current by store.state.collectAsState()
                MailListScreen(
                    title = "Inbox",
                    state = current,
                    listState = rememberMailListState(current.identity, current.rows, null),
                    offline = null,
                    refreshing = false,
                    onBack = {
                    },
                    onRefresh = {
                    },
                    onOpenRow = {},
                    onLongPressRow = {},
                    onToggleSelection = {},
                    onUnreadOnly = {},
                    onThreaded = {},
                    onRetry = {},
                    onLoadOlder = {
                    },
                    onLoadNewer = {
                    },
                    onJumpToLatest = {},
                    onSelectLoaded = {},
                    onStopSelecting = {},
                    onLanded = {},
                    onViewport = {},
                    onDismissNotice = {},
                    actions =
                        ListActions.None.let {
                            ListActions(
                                it.notices,
                                "2 actions waiting for the network",
                                it.swipeEnd,
                                it.swipeStart,
                                it.onSwipe,
                                it.onUndo,
                                it.onRetryAction,
                            )
                        },
                )
            }
        }
        compose.onNodeWithText("2 actions waiting for the network").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
}
