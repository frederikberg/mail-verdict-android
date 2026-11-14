package com.frederikberg.mailverdict.kit.maillist

import com.frederikberg.mailverdict.contract.FolderResponse
import com.frederikberg.mailverdict.contract.MessageListResponse
import com.frederikberg.mailverdict.contract.MessageLocation
import com.frederikberg.mailverdict.contract.UnifiedFolderResponse
import com.frederikberg.mailverdict.kit.api.listFolders
import com.frederikberg.mailverdict.kit.api.listMessages
import com.frederikberg.mailverdict.kit.api.listUnifiedFolders
import com.frederikberg.mailverdict.kit.api.listUnifiedMessages
import com.frederikberg.mailverdict.kit.api.locateMessage
import com.frederikberg.mailverdict.kit.api.triggerSync
import com.frederikberg.mailverdict.kit.net.ApiClient

/** Every call a message list makes; [ApiMailListBackend] is the real one, a test double drives paging and merging rules without a server. */
interface MailListBackend {
    suspend fun page(
        scope: ListScope,
        threaded: Boolean,
        unreadOnly: Boolean,
        cursor: ListCursor,
        limit: Int,
    ): MessageListResponse

    suspend fun locate(messageId: String): MessageLocation

    suspend fun folders(accountId: String): List<FolderResponse>

    suspend fun unifiedViews(): List<UnifiedFolderResponse>

    suspend fun sync(accountId: String)
}

class ApiMailListBackend(
    private val api: ApiClient,
) : MailListBackend {
    override suspend fun page(
        scope: ListScope,
        threaded: Boolean,
        unreadOnly: Boolean,
        cursor: ListCursor,
        limit: Int,
    ): MessageListResponse {
        val before = (cursor as? ListCursor.OlderThan)?.messageId
        val after = (cursor as? ListCursor.NewerThan)?.messageId
        val around = (cursor as? ListCursor.Around)?.messageId
        val isSeen = if (unreadOnly) false else null
        return when (scope) {
            is ListScope.Folder ->
                api.listMessages(
                    scope.accountId,
                    scope.folderId,
                    threaded,
                    isSeen,
                    before,
                    after,
                    around,
                    limit,
                )
            is ListScope.Unified -> api.listUnifiedMessages(scope.name, threaded, isSeen, before, after, around, limit)
        }
    }

    override suspend fun locate(messageId: String) = api.locateMessage(messageId)

    override suspend fun folders(accountId: String) = api.listFolders(accountId)

    override suspend fun unifiedViews() = api.listUnifiedFolders()

    override suspend fun sync(accountId: String) = api.triggerSync(accountId)
}

/** The one preference a list keeps across launches: whether rows are grouped by conversation (on by default). */
interface MailListPreferences {
    fun threaded(): Boolean

    fun setThreaded(threaded: Boolean)
}

class InMemoryMailListPreferences(
    private var threaded: Boolean = true,
) : MailListPreferences {
    override fun threaded() = threaded

    override fun setThreaded(threaded: Boolean) {
        this.threaded = threaded
    }
}
