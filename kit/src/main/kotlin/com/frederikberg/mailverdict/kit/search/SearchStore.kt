package com.frederikberg.mailverdict.kit.search

import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val context: SearchContext = SearchContext(),
    val results: List<SearchHit> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val total: Int? = null,
    val hasSearched: Boolean = false,
    val hasMore: Boolean = false,
    val semanticState: SemanticState = SemanticState.Settled,
) {
    val resultsState: SearchResultsState get() = SearchSupport.resultsState(this)

    val rows: List<SearchRow> get() = SearchSupport.rows(results)
}

/** Remembers the last search's context between launches. */
interface SearchPersistence {
    fun load(): SearchContext?

    fun save(context: SearchContext)
}

class InMemorySearchPersistence(
    private var saved: SearchContext? = null,
) : SearchPersistence {
    override fun load(): SearchContext? = saved

    override fun save(context: SearchContext) {
        saved = context
    }
}

/**
 * Search in its two modes. Typing is debounced and cancels whatever was in flight, so a fast typist's
 * earlier keystrokes never race their later ones for which answer lands last; every other change (a
 * chip, the scope) searches at once. Semantic mode asks twice, the semantic query and the same
 * query as text, and shows the text list until the answer by meaning replaces it; when the server has
 * no embedding provider the text list stays and the state says why.
 */
class SearchStore(
    private val backend: SearchBackend,
    private val scope: CoroutineScope,
    private val persistence: SearchPersistence = InMemorySearchPersistence(),
    private val debounceMs: Long = DEBOUNCE_MS,
    initial: SearchContext? = null,
) {
    private val _state = MutableStateFlow(SearchState(context = initial ?: persistence.load() ?: SearchContext()))
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val lock = Any()
    private var debounceJob: Job? = null
    private var runJob: Job? = null

    /** The text field's new content; the search starts [debounceMs] after the last keystroke. */
    fun queryChanged(query: String) {
        val updated = _state.value.context.copy(query = query)
        _state.update { it.copy(context = updated) }
        persistence.save(updated)
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob =
                scope.launch {
                    delay(debounceMs)
                    start(includeFullText = true)
                }
        }
    }

    /** A chip or scope change: searches at once, with no debounce. */
    fun updateContext(new: SearchContext) {
        val old = _state.value
        if (new == old.context) return
        // A window change on a semantic list that is already showing answers only the semantic query:
        // the text list would only flash by.
        val windowOnly =
            old.hasSearched &&
                old.context.mode == SearchMode.Semantic &&
                new.copy(timeWindow = old.context.timeWindow) == old.context
        _state.update { it.copy(context = new) }
        persistence.save(new)
        start(includeFullText = !windowOnly)
    }

    /** Runs the current query now, whatever the debounce says. */
    fun runSearch() = start(includeFullText = true)

    /** The next older page of a text list; semantic answers are single-page. */
    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || current.isLoadingMore || current.context.mode != SearchMode.Text) return
        val cursor = nextCursor ?: return
        _state.update { it.copy(isLoadingMore = true) }
        val context = current.context
        val generation = currentGeneration
        scope.launch {
            val outcome = backend.search(context, cursor)
            // A new run replaced the list this page belongs to.
            if (generation != currentGeneration) return@launch
            if (outcome is SearchOutcome.Page) {
                nextCursor = outcome.nextCursor
                _state.update {
                    it.copy(
                        results = it.results + outcome.hits.filter { hit -> it.results.none { r -> r.id == hit.id } },
                        hasMore = outcome.hasMore,
                        isLoadingMore = false,
                    )
                }
            } else {
                _state.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    /** A mail change anywhere can move a result in or out of the matches; results are server-ranked, so the query is asked again. */
    fun follow(events: Flow<List<LiveEvent>>) {
        scope.launch {
            events.collect { batch ->
                val stale =
                    batch.any {
                        when (it.invalidation) {
                            is LiveInvalidation.Resync,
                            is LiveInvalidation.MailNew,
                            is LiveInvalidation.MailUpdated,
                            is LiveInvalidation.MailDeleted,
                            is LiveInvalidation.VerdictIssued,
                            -> true
                            else -> false
                        }
                    }
                if (stale && _state.value.hasSearched) {
                    // The list on screen stays put: a semantic list is not swapped for the text one on every mail.
                    start(includeFullText = _state.value.context.mode != SearchMode.Semantic)
                }
            }
        }
    }

    @Volatile
    private var nextCursor: String? = null
    private var generation = 0
    private val currentGeneration: Int get() = synchronized(lock) { generation }

    private fun start(includeFullText: Boolean) {
        synchronized(lock) {
            debounceJob?.cancel()
            runJob?.cancel()
            generation++
            runJob = scope.launch { perform(_state.value.context, includeFullText) }
        }
    }

    private suspend fun perform(
        context: SearchContext,
        includeFullText: Boolean,
    ) {
        nextCursor = null
        if (!context.isSearchable) {
            _state.update {
                it.copy(
                    results = emptyList(),
                    hasSearched = false,
                    hasMore = false,
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = null,
                    total = null,
                    semanticState = SemanticState.Settled,
                )
            }
            return
        }
        _state.update { it.copy(isLoading = true, isLoadingMore = false, errorMessage = null) }
        if (context.mode == SearchMode.Text) {
            _state.update { it.copy(semanticState = SemanticState.Settled) }
            apply(backend.search(context, null), SemanticState.Settled)
            return
        }
        coroutineScope {
            _state.update {
                it.copy(semanticState = if (includeFullText) SemanticState.Pending else SemanticState.Settled)
            }
            val semantic = async { backend.search(context, null) }
            val fullText = if (includeFullText) async { backend.search(context.fullTextCounterpart, null) } else null
            if (fullText != null) {
                launch {
                    val outcome = fullText.await()
                    if (outcome is SearchOutcome.Page) showFullText(outcome)
                }
            }
            val outcome = semantic.await()
            if (outcome is SearchOutcome.Failure && outcome.semanticUnavailable) {
                val fallback = fullText?.await() ?: backend.search(context.fullTextCounterpart, null)
                apply(fallback, SemanticState.Unavailable)
            } else {
                apply(outcome, SemanticState.Settled)
            }
        }
    }

    /** The text list shown while the semantic answer is on its way; ignored once that has landed. */
    private fun showFullText(page: SearchOutcome.Page) {
        _state.update {
            if (it.semanticState != SemanticState.Pending) {
                it
            } else {
                it.copy(results = page.hits, hasMore = false, total = page.total, hasSearched = true)
            }
        }
    }

    private fun apply(
        outcome: SearchOutcome,
        semanticState: SemanticState,
    ) {
        when (outcome) {
            is SearchOutcome.Page -> {
                nextCursor = outcome.nextCursor
                _state.update {
                    it.copy(
                        results = outcome.hits,
                        hasMore = outcome.hasMore,
                        total = outcome.total,
                        isLoading = false,
                        hasSearched = true,
                        semanticState = semanticState,
                    )
                }
            }
            is SearchOutcome.Failure -> {
                nextCursor = null
                _state.update {
                    it.copy(
                        results = emptyList(),
                        hasMore = false,
                        total = null,
                        errorMessage = outcome.message,
                        isLoading = false,
                        hasSearched = true,
                        semanticState = semanticState,
                    )
                }
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
