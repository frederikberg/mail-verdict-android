package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.SearchResponse
import com.frederikberg.mailverdict.contract.SemanticSearchResponse
import com.frederikberg.mailverdict.kit.net.ApiClient

/** Field-scoped text search ranked by field tier. [fields] narrows it to named fields; [folderIds] to folders. */
suspend fun ApiClient.search(
    text: String,
    accountId: String? = null,
    folderIds: Collection<String>? = null,
    fields: Collection<String>? = null,
    before: String? = null,
    limit: Int? = null,
    receivedAfter: String? = null,
    receivedBefore: String? = null,
    isSeen: Boolean? = null,
): SearchResponse =
    send(
        SearchResponse.serializer(),
        "/search",
        query =
            query("q" to text, "account_id" to accountId, "before" to before, "limit" to limit) +
                repeated("folder_ids", folderIds) +
                repeated("fields", fields) +
                query("received_after" to receivedAfter, "received_before" to receivedBefore, "is_seen" to isSeen),
    )

/** Semantic search; answers 503 `semantic_unavailable` when the server has no embedding provider. */
suspend fun ApiClient.semanticSearch(
    text: String,
    accountId: String? = null,
    folderIds: Collection<String>? = null,
    receivedAfter: String? = null,
    receivedBefore: String? = null,
): SemanticSearchResponse =
    send(
        SemanticSearchResponse.serializer(),
        "/embeddings/search",
        query =
            query("q" to text, "account_id" to accountId) +
                repeated("folder_ids", folderIds) +
                query("received_after" to receivedAfter, "received_before" to receivedBefore),
    )
