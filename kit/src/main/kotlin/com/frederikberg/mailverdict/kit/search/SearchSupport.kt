package com.frederikberg.mailverdict.kit.search

/** What the results area shows instead of a list, decided once so the screen never re-derives it from loose flags. */
sealed interface SearchResultsState {
    data object EnterQuery : SearchResultsState

    data object SelectAFolder : SearchResultsState

    data object Loading : SearchResultsState

    /** Semantic mode, the text query found nothing and the answer by meaning is still on its way. */
    data object SearchingByMeaning : SearchResultsState

    data class Error(
        val message: String,
    ) : SearchResultsState

    data object NoResults : SearchResultsState

    data object Results : SearchResultsState
}

/** Where semantic mode stands. */
enum class SemanticState {
    /** Nothing to add to the list on screen. */
    Settled,

    /** The list is the text answer, shown until the answer by meaning replaces it. */
    Pending,

    /** The server could not answer by meaning; the list is the text one. */
    Unavailable,
}

object SearchSupport {
    /**
     * The server orders a semantic result tier first, newest first within a tier; the client only marks
     * where tier 2 begins. A text hit has no tier and never gets a divider.
     */
    fun rows(hits: List<SearchHit>): List<SearchRow> {
        val rows = ArrayList<SearchRow>(hits.size + 1)
        var dividerPlaced = false
        for (hit in hits) {
            if (!dividerPlaced && (hit.tier ?: 0) >= 2) {
                rows += SearchRow.LessLikelyDivider
                dividerPlaced = true
            }
            rows += SearchRow.Hit(hit)
        }
        return rows
    }

    /** An empty folder scope is wrong whatever the query, so it is checked before the length gate. */
    fun resultsState(state: SearchState): SearchResultsState =
        when {
            state.context.folderIds?.isEmpty() == true -> SearchResultsState.SelectAFolder
            state.context.query
                .trim()
                .length < SearchContext.MIN_QUERY_LENGTH -> SearchResultsState.EnterQuery
            state.isLoading && !state.hasSearched -> SearchResultsState.Loading
            state.errorMessage != null -> SearchResultsState.Error(state.errorMessage)
            state.semanticState == SemanticState.Pending && state.results.isEmpty() ->
                SearchResultsState.SearchingByMeaning
            state.hasSearched && state.results.isEmpty() -> SearchResultsState.NoResults
            else -> SearchResultsState.Results
        }
}
