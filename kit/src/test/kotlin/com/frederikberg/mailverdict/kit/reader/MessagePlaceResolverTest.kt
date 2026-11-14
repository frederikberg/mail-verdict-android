package com.frederikberg.mailverdict.kit.reader

import com.frederikberg.mailverdict.contract.MessageLocation
import com.frederikberg.mailverdict.contract.UnifiedFolderResponse
import com.frederikberg.mailverdict.contract.UnifiedFolderSource
import com.frederikberg.mailverdict.kit.maillist.ListScope
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePlaceResolverTest {
    private val location = MessageLocation("m1", "a1", "f1", "t1")

    private fun view(
        id: String,
        name: String,
        vararg folders: String,
    ) = UnifiedFolderResponse(
        id = id,
        unifiedName = name,
        totalCount = 0,
        unreadCount = 0,
        folders = folders.map { UnifiedFolderSource(it, "a1", "Private", null, "INBOX", "inbox") },
    )

    private fun resolver(
        recent: RecentViewRecord = InMemoryRecentViewRecord(),
        views: List<UnifiedFolderResponse> = emptyList(),
        locate: suspend (String) -> MessageLocation = { location },
    ) = MessagePlaceResolver(locate, { views }, recent)

    @Test
    fun aMessageOpensInItsOwnFolder() =
        runBlocking {
            assertEquals(PlaceResolution.Found(ListScope.Folder("a1", "f1"), "m1"), resolver().resolve("m1"))
        }

    @Test
    fun afterLookingAtAUnifiedViewAMessageOpensInTheMostRecentOneContainingItsFolder() =
        runBlocking {
            val recent = InMemoryRecentViewRecord()
            recent.recordUnifiedView(UnifiedViewRef("u2", "Work"))
            recent.recordUnifiedView(UnifiedViewRef("u1", "Inboxes"))
            val views = listOf(view("u1", "Inboxes", "f1"), view("u2", "Work", "f1"), view("u3", "Other", "f9"))
            assertEquals(
                PlaceResolution.Found(ListScope.Unified("u1", "Inboxes"), "m1"),
                resolver(recent, views).resolve("m1"),
            )
            recent.recordUnifiedView(UnifiedViewRef("u2", "Work"))
            assertEquals(
                PlaceResolution.Found(ListScope.Unified("u2", "Work"), "m1"),
                resolver(recent, views).resolve("m1"),
            )
        }

    @Test
    fun aUnifiedViewThatDoesNotContainTheFolderIsNotUsed() =
        runBlocking {
            val recent = InMemoryRecentViewRecord().also { it.recordUnifiedView(UnifiedViewRef("u3", "Other")) }
            assertEquals(
                PlaceResolution.Found(ListScope.Folder("a1", "f1"), "m1"),
                resolver(recent, listOf(view("u3", "Other", "f9"))).resolve("m1"),
            )
        }

    @Test
    fun aFolderViewForgetsThatTheLastViewWasUnified() {
        val recent = InMemoryRecentViewRecord()
        recent.recordUnifiedView(UnifiedViewRef("u1", "Inboxes"))
        assertTrue(recent.lastViewWasUnified())
        recent.recordFolderView()
        assertTrue(!recent.lastViewWasUnified())
        assertEquals(listOf("u1"), recent.recentUnifiedViews().map { it.id })
    }

    @Test
    fun onlyTheFiveMostRecentViewsAreKept() {
        val recent = InMemoryRecentViewRecord()
        (1..7).forEach { recent.recordUnifiedView(UnifiedViewRef("u$it", "V$it")) }
        assertEquals(listOf("u7", "u6", "u5", "u4", "u3"), recent.recentUnifiedViews().map { it.id })
    }

    @Test
    fun aMessageThatIsGoneAndAServerThatIsUnreachableAreToldApart() =
        runBlocking {
            val gone = resolver(locate = { throw ApiError.Detail("Not Found", 404) }).resolve("m1")
            assertEquals(PlaceResolution.NotFound("That message no longer exists"), gone)
            val offline =
                resolver(
                    locate = { throw ApiError.Transport("no route") },
                ).resolve("m1") as PlaceResolution.NotFound
            assertTrue(offline.message.startsWith("Could not find the message's folder"))
        }
}
