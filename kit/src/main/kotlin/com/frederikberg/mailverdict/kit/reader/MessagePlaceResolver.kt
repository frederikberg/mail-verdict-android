package com.frederikberg.mailverdict.kit.reader

import com.frederikberg.mailverdict.contract.MessageLocation
import com.frederikberg.mailverdict.contract.UnifiedFolderResponse
import com.frederikberg.mailverdict.kit.maillist.ListScope
import com.frederikberg.mailverdict.kit.net.ApiError

/** A unified view addressed the way a list is: by id and name. */
data class UnifiedViewRef(
    val id: String,
    val name: String,
)

/**
 * Remembers whether the last mail list the person looked at was a unified view, and which views they
 * opened recently, so opening a message from outside the app lands in the same kind of list rather
 * than always the message's own folder.
 */
interface RecentViewRecord {
    fun lastViewWasUnified(): Boolean

    fun recentUnifiedViews(): List<UnifiedViewRef>

    fun recordFolderView()

    fun recordUnifiedView(view: UnifiedViewRef)
}

class InMemoryRecentViewRecord : RecentViewRecord {
    private var unified = false
    private var recent = listOf<UnifiedViewRef>()

    override fun lastViewWasUnified() = unified

    override fun recentUnifiedViews() = recent

    override fun recordFolderView() {
        unified = false
    }

    override fun recordUnifiedView(view: UnifiedViewRef) {
        unified = true
        recent = (listOf(view) + recent.filter { it.id != view.id }).take(MAX_RECENT)
    }

    companion object {
        const val MAX_RECENT = 5
    }
}

sealed interface PlaceResolution {
    /** Open [scope] around [messageId], then the reader on it. */
    data class Found(
        val scope: ListScope,
        val messageId: String,
    ) : PlaceResolution

    data class NotFound(
        val message: String,
    ) : PlaceResolution
}

/**
 * Where a message is now and the path to it: the one place a notification tap, an alert row and
 * search's "show in folder" all resolve "open this message" through, so they never drift into
 * different answers for the same question.
 */
class MessagePlaceResolver(
    private val locate: suspend (messageId: String) -> MessageLocation,
    private val unifiedViews: suspend () -> List<UnifiedFolderResponse>,
    private val recent: RecentViewRecord,
) {
    suspend fun resolve(messageId: String): PlaceResolution {
        val location =
            try {
                locate(messageId)
            } catch (e: ApiError) {
                return if (e is ApiError.Transport) {
                    PlaceResolution.NotFound("Could not find the message's folder: ${e.userMessage}")
                } else {
                    PlaceResolution.NotFound("That message no longer exists")
                }
            }
        return PlaceResolution.Found(scope(location), messageId)
    }

    private suspend fun scope(location: MessageLocation): ListScope {
        if (recent.lastViewWasUnified()) {
            val views = runCatching { unifiedViews() }.getOrNull().orEmpty()
            val containing = views.filter { view -> view.folders.any { it.folderId == location.folderId } }
            val recentIds = recent.recentUnifiedViews().map { it.id }
            val pick =
                containing.minByOrNull {
                    recentIds.indexOf(it.id).let { index ->
                        if (index <
                            0
                        ) {
                            Int.MAX_VALUE
                        } else {
                            index
                        }
                    }
                }
            if (pick != null) return ListScope.Unified(pick.id, pick.unifiedName)
        }
        return ListScope.Folder(location.accountId, location.folderId)
    }
}
