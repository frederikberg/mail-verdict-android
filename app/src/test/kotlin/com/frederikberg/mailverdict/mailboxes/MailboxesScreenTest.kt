package com.frederikberg.mailverdict.mailboxes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.frederikberg.mailverdict.kit.auth.AuthMode
import com.frederikberg.mailverdict.kit.fixtures.FixtureInterceptor
import com.frederikberg.mailverdict.kit.fixtures.Fixtures
import com.frederikberg.mailverdict.kit.live.OfflineNotice
import com.frederikberg.mailverdict.kit.live.Reachability
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.RequestFactory
import com.frederikberg.mailverdict.kit.stores.mailboxes.AccountConnectionState
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesFolderRow
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesState
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesStore
import com.frederikberg.mailverdict.kit.stores.mailboxes.MailboxesSupport
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
class MailboxesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val toggled = mutableListOf<String>()

    private fun fixtureState(): MailboxesState {
        val client = OkHttpClient.Builder().addInterceptor(FixtureInterceptor(Fixtures.routes)).build()
        val store =
            MailboxesStore(
                ApiClient(RequestFactory("https://fixture.invalid", "Test phone") { AuthMode.None }, client),
                CoroutineScope(Dispatchers.Unconfined),
            )
        runBlocking { store.load() }
        return store.state.value
    }

    private fun show(
        state: MailboxesState,
        offline: OfflineNotice? = null,
    ) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                MailboxesScreen(
                    state = state,
                    refreshing = false,
                    offline = offline,
                    onRefresh = { opened += "refresh" },
                    onToggleCollapsed = { toggled += it },
                    onOpenFolder = { opened += "folder:${it.displayName}" },
                    onOpenUnified = { opened += "unified:${it.name}" },
                    onOpenSettings = { opened += "settings" },
                )
            }
        }
    }

    @Test
    fun theOverviewListsUnifiedViewsThenEachAccountsFoldersWithCounts() {
        val state = fixtureState()
        show(state)

        compose.onNodeWithTag(MailboxesTags.unified(state.unifiedRows.first().id)).assertIsDisplayed()
        compose.onNodeWithText("Private").assertIsDisplayed()
        compose
            .onNodeWithTag(
                MailboxesTags.folder(
                    state.accountSections
                        .first()
                        .folders
                        .first()
                        .id,
                ),
            ).assertIsDisplayed()
        compose.onNodeWithText("12").assertIsDisplayed()
        compose.onNodeWithText("15").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/mailboxes.png")
    }

    @Test
    fun tappingAFolderOrAUnifiedViewOpensIt() {
        val state = fixtureState()
        show(state)
        val inbox =
            state.accountSections
                .first()
                .folders
                .first()

        compose.onNodeWithTag(MailboxesTags.folder(inbox.id)).performClick()
        compose.onNodeWithTag(MailboxesTags.unified(state.unifiedRows.first().id)).performClick()
        compose.onNodeWithTag(MailboxesTags.SETTINGS).performClick()

        assertEquals(listOf("folder:Inbox", "unified:Inboxes", "settings"), opened)
    }

    @Test
    fun aSectionHeaderTogglesItsKey() {
        val state = fixtureState()
        show(state)

        compose.onNodeWithTag(MailboxesTags.header(MailboxesSupport.UNIFIED_KEY)).performClick()
        compose.onNodeWithTag(MailboxesTags.header(state.accountSections.first().collapseKey)).performClick()

        assertEquals(listOf(MailboxesSupport.UNIFIED_KEY, state.accountSections.first().collapseKey), toggled)
    }

    @Test
    fun aCollapsedSectionHidesItsRows() {
        val state = fixtureState()
        show(state.copy(collapsedKeys = setOf(state.accountSections.first().collapseKey)))

        compose.onNodeWithText("Private").assertIsDisplayed()
        compose.onNodeWithText("Receipts").assertDoesNotExist()
        compose.onNodeWithTag(MailboxesTags.unified(state.unifiedRows.first().id)).assertIsDisplayed()
    }

    @Test
    fun anAccountThatNeverConnectedShowsItsErrorInsteadOfFolders() {
        val state = fixtureState()
        val broken =
            state.accountSections.first().copy(
                connectionState = AccountConnectionState.NeverConnected,
                stateError = "Login failed",
                folders = emptyList(),
            )
        show(state.copy(accountSections = listOf(broken)))

        compose.onNodeWithText("Login failed").assertIsDisplayed()
        compose.onNodeWithText("Error").assertIsDisplayed()
    }

    @Test
    fun theDeadOutboxBannerAndALoadErrorAreShown() {
        show(
            fixtureState().copy(
                deadOutboxBanner = "2 messages could not be sent — check SMTP settings on Work.",
                loadError = "boom",
            ),
        )

        compose.onNodeWithText("2 messages could not be sent", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Could not load your mailboxes: boom").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("refresh"), opened)
    }

    @Test
    fun aPersonWithNoAccountIsToldSo() {
        show(MailboxesState(hasAnyAccount = false))

        compose.onNodeWithText("No account is connected to this server yet.").assertIsDisplayed()
    }

    @Test
    fun offlineShowsTheCapsuleOverTheStillUsableList() {
        val state = fixtureState()
        show(state, OfflineNotice.make(Reachability.ServerUnreachable))

        compose.onNodeWithText("Server unreachable").assertIsDisplayed()
        compose
            .onNodeWithTag(
                MailboxesTags.folder(
                    state.accountSections
                        .first()
                        .folders
                        .first()
                        .id,
                ),
            ).performClick()
        assertEquals(1, opened.size)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/mailboxes-offline.png")
    }

    @Test
    fun aGlacierFolderRowIsDistinctFromARealOne() {
        val row =
            MailboxesFolderRow(
                "g",
                "a",
                "Glacier",
                null,
                0,
                10,
                com.frederikberg.mailverdict.contract.FolderKind.Glacier,
            )

        assertEquals(true, row.isGlacier)
    }
}
