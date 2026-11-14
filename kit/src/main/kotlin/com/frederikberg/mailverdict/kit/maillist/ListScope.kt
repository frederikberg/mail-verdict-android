package com.frederikberg.mailverdict.kit.maillist

import kotlinx.serialization.Serializable

/** Which messages a list shows: one folder of one account, or one unified view addressed by its name. */
@Serializable
sealed interface ListScope {
    @Serializable
    data class Folder(
        val accountId: String,
        val folderId: String,
    ) : ListScope

    @Serializable
    data class Unified(
        val viewId: String,
        val name: String,
    ) : ListScope

    /** A stable text identity, usable as a map key and in a file name. */
    val key: String
        get() =
            when (this) {
                is Folder -> "folder-$accountId-$folderId"
                is Unified -> "unified-$viewId"
            }
}

/**
 * Which list the rows on screen are. Any change here is a different list and starts at the top
 * instead of inheriting the previous list's offset, clamped to a different height.
 */
data class ListIdentity(
    val scope: ListScope,
    val threaded: Boolean,
    val unreadOnly: Boolean,
    /** Bumped whenever the rows are replaced by a fresh read of otherwise the same list: a jump to the newest mail. */
    val generation: Int = 0,
)

/** Where a page of a list starts. */
sealed interface ListCursor {
    /** The newest edge. */
    data object Newest : ListCursor

    /** Continuing older, below this message. */
    data class OlderThan(
        val messageId: String,
    ) : ListCursor

    /** Continuing newer, above this message; only meaningful once a window opened away from the newest edge. */
    data class NewerThan(
        val messageId: String,
    ) : ListCursor

    /** A fresh page centred on this message. The server answers 404 when it is not a member of the list. */
    data class Around(
        val messageId: String,
    ) : ListCursor
}
