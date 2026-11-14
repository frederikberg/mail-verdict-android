package com.frederikberg.mailverdict.kit.search

import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchStoreTest {
    private fun hit(
        id: String,
        tier: Int? = null,
    ) = SearchHit(
        id,
        "a",
        "f",
        "t-$id",
        "Subject $id",
        "Ada <ada@example.com>",
        null,
        false,
        false,
        false,
        false,
        null,
        null,
        null,
        tier,
    )

    private fun page(
        vararg ids: String,
        hasMore: Boolean = false,
        cursor: String? = null,
        tier: Int? = null,
    ) = SearchOutcome.Page(ids.map { hit(it, tier) }, hasMore, cursor, ids.size)

    /** Records every request and answers from a script keyed by mode and query. */
    private class FakeBackend(
        private val delays: Map<String, Long> = emptyMap(),
        private val answer: (SearchContext, String?) -> SearchOutcome,
    ) : SearchBackend {
        val calls = mutableListOf<Pair<SearchContext, String?>>()

        override suspend fun search(
            context: SearchContext,
            before: String?,
        ): SearchOutcome {
            calls += context to before
            delays["${context.mode}:${context.query}"]?.let { delay(it) }
            return answer(context, before)
        }
    }

    @Test
    fun keystrokesWithinTheDebounceBecomeOneSearchForTheLastQuery() =
        runTest {
            val backend = FakeBackend { _, _ -> page("1") }
            val store = SearchStore(backend, this)
            store.queryChanged("in")
            advanceTimeBy(200)
            store.queryChanged("inv")
            advanceTimeBy(200)
            store.queryChanged("invoice")
            advanceTimeBy(299)
            assertTrue("nothing is sent before the pause", backend.calls.isEmpty())
            advanceUntilIdle()
            assertEquals(listOf("invoice"), backend.calls.map { it.first.query })
            assertEquals(
                listOf("1"),
                store.state.value.results
                    .map { it.id },
            )
        }

    @Test
    fun aQueryTooShortToSearchNeverReachesTheServer() =
        runTest {
            val backend = FakeBackend { _, _ -> page("1") }
            val store = SearchStore(backend, this)
            store.queryChanged("i")
            advanceUntilIdle()
            assertTrue(backend.calls.isEmpty())
            assertEquals(SearchResultsState.EnterQuery, store.state.value.resultsState)
        }

    @Test
    fun anEmptyFolderScopeSearchesNothingWhateverTheQuery() =
        runTest {
            val backend = FakeBackend { _, _ -> page("1") }
            val store = SearchStore(backend, this, initial = SearchContext(query = "invoice", folderIds = emptyList()))
            store.runSearch()
            advanceUntilIdle()
            assertTrue(backend.calls.isEmpty())
            assertEquals(SearchResultsState.SelectAFolder, store.state.value.resultsState)
        }

    @Test
    fun aChipSearchesAtOnceAndCancelsTheTypingDebounce() =
        runTest {
            val backend = FakeBackend { _, _ -> page("1") }
            val store = SearchStore(backend, this)
            store.queryChanged("invoice")
            store.updateContext(
                store.state.value.context
                    .copy(fields = listOf(SearchField.Subject)),
            )
            advanceUntilIdle()
            assertEquals("one search, with the chip applied", 1, backend.calls.size)
            assertEquals(
                listOf(SearchField.Subject),
                backend.calls
                    .single()
                    .first.fields,
            )
        }

    @Test
    fun aSlowEarlierSearchNeverOverwritesALaterOne() =
        runTest {
            val backend =
                FakeBackend(delays = mapOf("Text:slow" to 1_000, "Text:fast" to 10)) { context, _ ->
                    if (context.query == "slow") page("old") else page("new")
                }
            val store = SearchStore(backend, this, debounceMs = 0)
            store.queryChanged("slow")
            advanceTimeBy(1)
            store.queryChanged("fast")
            advanceUntilIdle()
            assertEquals(
                listOf("new"),
                store.state.value.results
                    .map { it.id },
            )
        }

    @Test
    fun semanticResultsKeepTheirTierOrderAndMarkWhereLessLikelyBegins() {
        val rows = SearchSupport.rows(listOf(hit("a", 1), hit("b", 1), hit("c", 2), hit("d", 2)))
        assertEquals(
            listOf("result:a", "result:b", "divider:less-likely", "result:c", "result:d"),
            rows.map { it.key },
        )
        assertTrue(
            "a text list never gets a divider",
            SearchSupport.rows(listOf(hit("a"), hit("b"))).none {
                it is SearchRow.LessLikelyDivider
            },
        )
        assertEquals(
            "a list with only likely hits has no divider",
            2,
            SearchSupport.rows(listOf(hit("a", 1), hit("b", 1))).size,
        )
    }

    @Test
    fun semanticModeShowsTheTextListAtOnceAndReplacesItWhenMeaningArrives() =
        runTest {
            val backend =
                FakeBackend(delays = mapOf("Semantic:contract" to 500)) { context, _ ->
                    if (context.mode == SearchMode.Text) page("text-1", "text-2") else page("sem-1", "sem-2", tier = 1)
                }
            val store =
                SearchStore(backend, this, initial = SearchContext(mode = SearchMode.Semantic, query = "contract"))
            store.runSearch()
            advanceTimeBy(100)
            assertEquals(SemanticState.Pending, store.state.value.semanticState)
            assertEquals(
                listOf("text-1", "text-2"),
                store.state.value.results
                    .map { it.id },
            )
            advanceUntilIdle()
            assertEquals(SemanticState.Settled, store.state.value.semanticState)
            assertEquals(
                listOf("sem-1", "sem-2"),
                store.state.value.results
                    .map { it.id },
            )
            assertEquals(
                "the text query is the same query with every field",
                SearchMode.Text,
                backend.calls
                    .first { it.first.mode == SearchMode.Text }
                    .first.mode,
            )
        }

    @Test
    fun aLateTextListDoesNotReplaceSemanticResultsAlreadyShown() =
        runTest {
            val backend =
                FakeBackend(delays = mapOf("Text:contract" to 500)) { context, _ ->
                    if (context.mode == SearchMode.Text) page("text-1") else page("sem-1", tier = 1)
                }
            val store =
                SearchStore(backend, this, initial = SearchContext(mode = SearchMode.Semantic, query = "contract"))
            store.runSearch()
            advanceUntilIdle()
            assertEquals(
                listOf("sem-1"),
                store.state.value.results
                    .map { it.id },
            )
        }

    @Test
    fun whenTheServerCannotAnswerByMeaningTheTextListStaysAndTheStateSaysSo() =
        runTest {
            val backend =
                FakeBackend { context, _ ->
                    if (context.mode == SearchMode.Semantic) {
                        SearchOutcome.Failure("semantic_unavailable", semanticUnavailable = true)
                    } else {
                        page("text-1")
                    }
                }
            val store =
                SearchStore(backend, this, initial = SearchContext(mode = SearchMode.Semantic, query = "contract"))
            store.runSearch()
            advanceUntilIdle()
            assertEquals(SemanticState.Unavailable, store.state.value.semanticState)
            assertEquals(
                listOf("text-1"),
                store.state.value.results
                    .map { it.id },
            )
            assertNull(store.state.value.errorMessage)
        }

    @Test
    fun anOrdinaryFailureOfTheSemanticQueryIsAnErrorNotAFallback() =
        runTest {
            val backend =
                FakeBackend { context, _ ->
                    if (context.mode == SearchMode.Semantic) SearchOutcome.Failure("boom", false) else page("text-1")
                }
            val store =
                SearchStore(backend, this, initial = SearchContext(mode = SearchMode.Semantic, query = "contract"))
            store.runSearch()
            advanceUntilIdle()
            assertEquals(SearchResultsState.Error("boom"), store.state.value.resultsState)
        }

    @Test
    fun aWindowChangeOnASemanticListAsksOnlyTheSemanticQuery() =
        runTest {
            val backend =
                FakeBackend { context, _ ->
                    page(
                        "x",
                        tier =
                            if (context.mode ==
                                SearchMode.Semantic
                            ) {
                                1
                            } else {
                                null
                            },
                    )
                }
            val store =
                SearchStore(backend, this, initial = SearchContext(mode = SearchMode.Semantic, query = "contract"))
            store.runSearch()
            advanceUntilIdle()
            backend.calls.clear()
            store.updateContext(
                store.state.value.context
                    .copy(timeWindow = TimeWindow.Last7Days),
            )
            advanceUntilIdle()
            assertEquals(listOf(SearchMode.Semantic), backend.calls.map { it.first.mode })
        }

    @Test
    fun loadMoreContinuesFromTheCursorAndNeverRepeatsARow() =
        runTest {
            val backend =
                FakeBackend { _, before ->
                    if (before ==
                        null
                    ) {
                        page("1", "2", hasMore = true, cursor = "c1")
                    } else {
                        page("2", "3", hasMore = false)
                    }
                }
            val store = SearchStore(backend, this, initial = SearchContext(query = "invoice"))
            store.runSearch()
            advanceUntilIdle()
            store.loadMore()
            advanceUntilIdle()
            assertEquals(
                listOf("1", "2", "3"),
                store.state.value.results
                    .map { it.id },
            )
            assertEquals("c1", backend.calls.last().second)
            assertEquals(false, store.state.value.hasMore)
        }

    @Test
    fun aPageThatArrivesAfterANewSearchBelongsToTheOldListAndIsDropped() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val backend =
                object : SearchBackend {
                    override suspend fun search(
                        context: SearchContext,
                        before: String?,
                    ): SearchOutcome {
                        if (before != null) gate.await()
                        return if (context.query == "invoice") page("1", hasMore = true, cursor = "c") else page("n")
                    }
                }
            val store = SearchStore(backend, this, initial = SearchContext(query = "invoice"))
            store.runSearch()
            advanceUntilIdle()
            store.loadMore()
            advanceTimeBy(1)
            store.updateContext(
                store.state.value.context
                    .copy(query = "other"),
            )
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf("n"),
                store.state.value.results
                    .map { it.id },
            )
        }

    @Test
    fun aMailChangeAsksTheSameQueryAgainButOnlyOnceSomethingWasSearched() =
        runTest {
            val backend = FakeBackend { _, _ -> page("1") }
            val events = MutableSharedFlow<List<LiveEvent>>()
            val store = SearchStore(backend, this, initial = SearchContext(query = "invoice"))
            store.follow(events)
            advanceUntilIdle()
            events.emit(listOf(LiveEvent(LiveInvalidation.MailNew("a", "f", "m"), 0)))
            advanceUntilIdle()
            assertTrue("nothing was searched yet, so nothing to refresh", backend.calls.isEmpty())
            store.runSearch()
            advanceUntilIdle()
            events.emit(listOf(LiveEvent(LiveInvalidation.MailNew("a", "f", "m"), 0)))
            advanceUntilIdle()
            events.emit(listOf(LiveEvent(LiveInvalidation.AlertsChanged, 0)))
            advanceUntilIdle()
            assertEquals("the first search and one refresh; an alert event changes nothing", 2, backend.calls.size)
            coroutineContext.cancelChildren()
        }

    @Test
    fun theLastContextIsRemembered() =
        runTest {
            val persistence = InMemorySearchPersistence()
            val store = SearchStore(FakeBackend { _, _ -> page("1") }, this, persistence)
            store.queryChanged("invoice")
            store.updateContext(
                store.state.value.context
                    .copy(mode = SearchMode.Semantic),
            )
            advanceUntilIdle()
            assertEquals(SearchMode.Semantic, persistence.load()?.mode)
            assertEquals("invoice", persistence.load()?.query)
        }
}
