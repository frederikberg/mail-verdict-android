package com.frederikberg.mailverdict.kit.maillist

/** Where the list is in loading its first page. */
sealed interface ListPhase {
    data object Loading : ListPhase

    data object Loaded : ListPhase

    data class Failed(
        val message: String,
    ) : ListPhase
}

/** Where the screen should put the reader once the first page of a list has landed. */
sealed interface ListLanding {
    /** A message opened from outside the list (a notification, "show in folder"): its row a third of the way down the screen. */
    data class RevealInUpperThird(
        val rowId: String,
    ) : ListLanding
}

/** Counts and roles the title, subtitle and rows need; every part is optional because a list shows its rows before any of it arrives. */
data class ListContext(
    /** Special-use role by folder id, so a Sent or Drafts row shows recipients. */
    val folderRoles: Map<String, String?> = emptyMap(),
    val totalCount: Int? = null,
    val unreadCount: Int? = null,
    /** Member folders of a unified view; empty for a folder scope. */
    val memberFolderIds: Set<String> = emptySet(),
    val accountIds: Set<String> = emptySet(),
)

/** Everything a list screen draws. */
data class MailListState(
    val identity: ListIdentity,
    /** The grouping toggle as the person set it; [identity] follows when the page for it has landed. */
    val threaded: Boolean = identity.threaded,
    val unreadOnly: Boolean = identity.unreadOnly,
    val rows: List<MailRowData> = emptyList(),
    val phase: ListPhase = ListPhase.Loading,
    val hasOlder: Boolean = false,
    /** The window does not start at the newest message: it opened around one. */
    val hasNewer: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isLoadingNewer: Boolean = false,
    /** Arrivals recorded while the window has a gap above it: never added to the rows, only counted. */
    val pendingArrivals: Int = 0,
    /** When the rows on screen were saved, while they come from the offline cache because the server could not be reached. */
    val savedCopyAtMs: Long? = null,
    val landing: ListLanding? = null,
    val isSelecting: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val context: ListContext = ListContext(),
    /** A one-off message for the person (a pull to refresh that could not reach the server); cleared by [MailListStore.dismissNotice]. */
    val notice: String? = null,
) {
    val windowAtNewestEdge: Boolean get() = !hasNewer

    /** The total or, while only unread mail is listed, the unread count; null until it has loaded. */
    val countLabel: String?
        get() {
            val count = (if (unreadOnly) context.unreadCount else context.totalCount) ?: return null
            val noun = if (count == 1) "message" else "messages"
            return if (unreadOnly) "$count unread" else "$count $noun"
        }

    val emptyMessage: String
        get() =
            when {
                unreadOnly -> "No unread messages here"
                identity.scope is ListScope.Unified -> "No messages in this view"
                else -> "No messages in this folder"
            }
}
