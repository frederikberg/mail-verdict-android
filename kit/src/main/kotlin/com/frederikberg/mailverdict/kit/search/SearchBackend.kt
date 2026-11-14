package com.frederikberg.mailverdict.kit.search

import com.frederikberg.mailverdict.kit.api.search
import com.frederikberg.mailverdict.kit.api.semanticSearch
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

/** What one search request came back with. */
sealed interface SearchOutcome {
    data class Page(
        val hits: List<SearchHit>,
        val hasMore: Boolean,
        val nextCursor: String?,
        val total: Int?,
    ) : SearchOutcome

    /** [semanticUnavailable]: a semantic query the server could not embed (HTTP 503), the one failure that has a fallback. */
    data class Failure(
        val message: String,
        val semanticUnavailable: Boolean,
    ) : SearchOutcome
}

/** The seam between the store and the network, so the store's timing rules run in virtual time. */
fun interface SearchBackend {
    suspend fun search(
        context: SearchContext,
        before: String?,
    ): SearchOutcome
}

/** Asks the server. A preset window is resolved at the moment of each request. */
class ApiSearchBackend(
    private val api: ApiClient,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : SearchBackend {
    override suspend fun search(
        context: SearchContext,
        before: String?,
    ): SearchOutcome {
        val bounds = context.timeWindow?.bounds(now(), zone())
        val after = bounds?.first?.toString()
        val until = bounds?.second?.toString()
        return try {
            when (context.mode) {
                SearchMode.Text -> {
                    val response =
                        api.search(
                            text = context.query.trim(),
                            accountId = context.accountId,
                            folderIds = context.folderIds,
                            fields = context.fields?.map { it.wire },
                            before = before,
                            receivedAfter = after,
                            receivedBefore = until,
                        )
                    SearchOutcome.Page(
                        response.results.map(SearchHit::of),
                        response.hasMore,
                        response.nextCursor,
                        response.total,
                    )
                }
                SearchMode.Semantic -> {
                    val response =
                        api.semanticSearch(
                            text = context.query.trim(),
                            accountId = context.accountId,
                            folderIds = context.folderIds,
                            receivedAfter = after,
                            receivedBefore = until,
                        )
                    SearchOutcome.Page(
                        response.results.map(SearchHit::of),
                        hasMore = false,
                        nextCursor = null,
                        total = response.results.size,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            SearchOutcome.Failure(
                e.userMessage,
                semanticUnavailable = context.mode == SearchMode.Semantic && e.statusCode == SEMANTIC_UNAVAILABLE,
            )
        }
    }

    private companion object {
        const val SEMANTIC_UNAVAILABLE = 503
    }
}
