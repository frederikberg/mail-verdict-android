package com.frederikberg.mailverdict.kit.search

import com.frederikberg.mailverdict.contract.SearchResult
import com.frederikberg.mailverdict.contract.SemanticSearchHit

/**
 * A search result as the list shows it. Text and semantic hits share this shape; [tier] is the
 * semantic tier (1 likely, 2 less likely) and null for a text hit.
 */
data class SearchHit(
    val id: String,
    val accountId: String,
    val folderId: String,
    val threadId: String,
    val subject: String?,
    val fromAddr: String?,
    val receivedAt: String?,
    val isSeen: Boolean,
    val isFlagged: Boolean,
    val hasAttachments: Boolean,
    val isGlacier: Boolean,
    val snippet: String?,
    val threadCount: Int?,
    val verdictIsSpam: Boolean?,
    val tier: Int? = null,
) {
    companion object {
        fun of(result: SearchResult) =
            SearchHit(
                id = result.id,
                accountId = result.accountId,
                folderId = result.folderId,
                threadId = result.threadId,
                subject = result.subject,
                fromAddr = result.fromAddr,
                receivedAt = result.receivedAt,
                isSeen = result.isSeen,
                isFlagged = result.isFlagged,
                hasAttachments = result.hasAttachments,
                isGlacier = result.isGlacier,
                snippet = result.snippet,
                threadCount = result.threadCount,
                verdictIsSpam = result.verdictIsSpam,
            )

        fun of(hit: SemanticSearchHit) =
            SearchHit(
                id = hit.id,
                accountId = hit.accountId,
                folderId = hit.folderId,
                threadId = hit.threadId,
                subject = hit.subject,
                fromAddr = hit.fromAddr,
                receivedAt = hit.receivedAt,
                isSeen = hit.isSeen,
                isFlagged = hit.isFlagged,
                hasAttachments = hit.hasAttachments,
                isGlacier = hit.isGlacier,
                snippet = hit.snippet,
                threadCount = hit.threadCount,
                verdictIsSpam = hit.verdictIsSpam,
                tier = hit.tier,
            )
    }
}

/** One line of the results list: a hit, or the divider ahead of the first less likely one. */
sealed interface SearchRow {
    /** Stable across refetches: the message id, or a fixed key for the divider. */
    val key: String

    data class Hit(
        val hit: SearchHit,
    ) : SearchRow {
        override val key: String get() = "result:${hit.id}"
    }

    data object LessLikelyDivider : SearchRow {
        override val key: String = "divider:less-likely"
    }
}
