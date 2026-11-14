package com.frederikberg.mailverdict.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import com.frederikberg.mailverdict.kit.search.SearchContext
import com.frederikberg.mailverdict.kit.search.SearchField
import com.frederikberg.mailverdict.kit.search.SearchHit
import com.frederikberg.mailverdict.kit.search.SearchMode
import com.frederikberg.mailverdict.kit.search.SearchState
import com.frederikberg.mailverdict.kit.search.SemanticState
import com.frederikberg.mailverdict.ui.theme.MailVerdictTheme
import com.github.takahirom.roborazzi.captureRoboImage
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
class SearchScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val queries = mutableListOf<String>()
    private val contexts = mutableListOf<SearchContext>()
    private val opened = mutableListOf<String>()
    private var loadMoreCalls = 0

    private fun hit(
        n: Int,
        tier: Int? = null,
        unread: Boolean = false,
    ) = SearchHit(
        id = "h$n",
        accountId = "a",
        folderId = "f",
        threadId = "t$n",
        subject = "Invoice number $n",
        fromAddr = "Ada Lovelace <ada@example.com>",
        receivedAt = "2026-03-0${1 + n % 8}T08:15:00+00:00",
        isSeen = !unread,
        isFlagged = false,
        hasAttachments = false,
        isGlacier = false,
        snippet = "Please find the invoice for last month attached, payable within fourteen days of receipt.",
        threadCount = null,
        verdictIsSpam = null,
        tier = tier,
    )

    private fun callbacks() =
        SearchCallbacks(
            onBack = { opened += "back" },
            onQuery = { queries += it },
            onContext = { contexts += it },
            onOpen = { opened += it.id },
            onLoadMore = { loadMoreCalls++ },
        )

    private fun show(
        state: SearchState,
        scopeLabel: String? = null,
    ) {
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                SearchScreen(state, initialQuery = state.context.query, scopeLabel, callbacks(), focusOnOpen = false)
            }
        }
    }

    private fun results(
        hits: List<SearchHit>,
        context: SearchContext = SearchContext(query = "invoice"),
        semantic: SemanticState = SemanticState.Settled,
        hasMore: Boolean = false,
    ) = SearchState(context, hits, hasSearched = true, hasMore = hasMore, total = hits.size, semanticState = semantic)

    @Test
    fun textResultsShowSenderSubjectAndSnippetAndOpenOnTap() {
        show(results(listOf(hit(1, unread = true), hit(2))))
        compose.onNodeWithTag(SearchTags.row("h1")).assertIsDisplayed()
        compose.onNodeWithText("Invoice number 2").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/search-text-results.png")
        compose.onNodeWithTag(SearchTags.row("h2")).performClick()
        assertEquals(listOf("h2"), opened)
    }

    @Test
    fun semanticResultsPutTheLessLikelyDividerAheadOfTierTwo() {
        val state =
            results(
                listOf(hit(1, tier = 1), hit(2, tier = 1), hit(3, tier = 2), hit(4, tier = 2)),
                SearchContext(mode = SearchMode.Semantic, query = "contract"),
            )
        show(state)
        compose.onNodeWithTag(SearchTags.DIVIDER).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/search-semantic-tiers.png")
    }

    @Test
    fun aTextListNeverShowsTheDivider() {
        show(results(listOf(hit(1), hit(2))))
        compose.onNodeWithTag(SearchTags.DIVIDER).assertDoesNotExist()
    }

    @Test
    fun whenMeaningIsUnavailableTheTextListIsShownWithAnExplanation() {
        show(
            results(
                listOf(hit(1)),
                SearchContext(mode = SearchMode.Semantic, query = "contract"),
                semantic = SemanticState.Unavailable,
            ),
        )
        compose.onNodeWithTag(SearchTags.SEMANTIC_NOTE).assertIsDisplayed()
        compose.onNodeWithText("Invoice number 1").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/search-semantic-unavailable.png")
    }

    @Test
    fun theStatesWithoutResultsSayWhatToDo() {
        show(SearchState(SearchContext(query = "i")))
        compose.onNodeWithTag(SearchTags.STATE).assertTextEquals("Type at least two letters to search.")
    }

    @Test
    fun noMatchesIsSaidInWords() {
        show(SearchState(SearchContext(query = "zzzz"), hasSearched = true))
        compose.onNodeWithTag(SearchTags.STATE).assertTextEquals("Nothing matches.")
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/search-no-results.png")
    }

    @Test
    fun anEmptyFolderScopeSaysNothingIsSelected() {
        show(SearchState(SearchContext(query = "invoice", folderIds = emptyList())))
        compose.onNodeWithTag(SearchTags.STATE).assertTextEquals("No folder is selected to search in.")
    }

    @Test
    fun typingReportsEveryChangeToTheStore() {
        show(SearchState())
        compose.onNodeWithTag(SearchTags.FIELD).performTextInput("inv")
        assertEquals(listOf("inv"), queries)
    }

    @Test
    fun aFieldChipNarrowsTheSearchAndTheSecondTapLiftsIt() {
        show(SearchState(SearchContext(query = "invoice")))
        compose.onNodeWithTag(SearchTags.field(SearchField.Subject)).performScrollTo().performClick()
        assertEquals(listOf(SearchField.Subject), contexts.last().fields)
    }

    @Test
    fun deselectingTheLastFieldChipMeansEveryField() {
        show(SearchState(SearchContext(query = "invoice", fields = listOf(SearchField.Subject))))
        compose.onNodeWithTag(SearchTags.field(SearchField.Subject)).performScrollTo().performClick()
        assertEquals(null, contexts.last().fields)
    }

    @Test
    fun theModeChipSwitchesToSemantic() {
        show(SearchState(SearchContext(query = "invoice")))
        compose.onNodeWithTag(SearchTags.MODE_SEMANTIC).performClick()
        assertEquals(SearchMode.Semantic, contexts.last().mode)
    }

    @Test
    fun theTimeWindowMenuOffersEveryPreset() {
        show(SearchState(SearchContext(query = "invoice")))
        compose.onNodeWithTag(SearchTags.WINDOW).performClick()
        compose.onNodeWithText("Last 7 days").performClick()
        assertEquals(com.frederikberg.mailverdict.kit.search.TimeWindow.Last7Days, contexts.last().timeWindow)
    }

    @Test
    fun theScopeChipNamesWhereSearchWasOpenedAndLiftsTheScope() {
        show(
            SearchState(SearchContext(query = "invoice", accountId = "a", folderIds = listOf("f"))),
            scopeLabel = "Inbox",
        )
        compose.onNodeWithText("In Inbox").assertIsDisplayed()
        compose.onNodeWithTag(SearchTags.SCOPE).performClick()
        assertEquals(null, contexts.last().accountId)
        assertEquals(null, contexts.last().folderIds)
    }

    @Test
    fun aNewQueryStartsTheListAtTheTopInsteadOfKeepingTheOldOffset() {
        val many = (1..60).map { hit(it) }
        var state by mutableStateOf(results(many, SearchContext(query = "invoice")))
        compose.setContent {
            MailVerdictTheme(dynamicColor = false) {
                SearchScreen(state, initialQuery = "invoice", scopeLabel = null, callbacks(), focusOnOpen = false)
            }
        }
        compose.onNodeWithTag(SearchTags.LIST).performScrollToIndex(45)
        compose.onNodeWithTag(SearchTags.row("h1")).assertDoesNotExist()
        state = results(many.map { it.copy(id = "n${it.id}") }, SearchContext(query = "invoices"))
        compose.waitForIdle()
        compose.onNodeWithTag(SearchTags.row("nh1")).assertIsDisplayed()
    }

    @Test
    fun theNextPageIsAskedForBeforeTheListEndsAndOnlyWhenThereIsOne() {
        val many = (1..60).map { hit(it) }
        show(results(many, hasMore = false))
        compose.onNodeWithTag(SearchTags.LIST).performScrollToIndex(55)
        compose.waitForIdle()
        assertEquals("no page to ask for", 0, loadMoreCalls)
    }

    @Test
    fun scrollingTowardsTheEndAsksForTheNextPage() {
        val many = (1..60).map { hit(it) }
        show(results(many, hasMore = true))
        compose.onNodeWithTag(SearchTags.LIST).performScrollToIndex(45)
        compose.waitForIdle()
        assertTrue("asked at least once", loadMoreCalls >= 1)
    }

    @Test
    fun scrollingNearTheTopDoesNotAskForAPage() {
        val many = (1..60).map { hit(it) }
        show(results(many, hasMore = true))
        compose.waitForIdle()
        assertEquals(0, loadMoreCalls)
    }
}
