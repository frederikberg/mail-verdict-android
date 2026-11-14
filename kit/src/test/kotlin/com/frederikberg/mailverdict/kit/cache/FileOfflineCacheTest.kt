package com.frederikberg.mailverdict.kit.cache

import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.contract.ThreadResponse
import com.frederikberg.mailverdict.kit.maillist.ListScope
import com.frederikberg.mailverdict.kit.maillist.message
import com.frederikberg.mailverdict.kit.maillist.rows
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileOfflineCacheTest {
    private val dir = Files.createTempDirectory("offline-cache").toFile()
    private var clock = 1_000L
    private val key = OfflineListKey(ListScope.Folder("a1", "f1"), threaded = true, unreadOnly = false)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun cache(budget: FileOfflineCache.Budget = FileOfflineCache.Budget()) =
        FileOfflineCache(dir, budget) {
            clock++
        }

    private fun detail(n: Int) =
        MessageDetail(
            id = "m$n",
            accountId = "a1",
            folderId = "f1",
            threadId = "t",
            createdAt = "2026-11-01T00:00:00+00:00",
            subject = "Hello $n",
            bodyText = "Body $n",
        )

    @Test
    fun aSavedListComesBackWithItsRowsAndWhenItWasSaved() =
        runBlocking {
            val cache = cache()
            cache.saveList(key, rows(30 downTo 1), hasOlder = true)
            val saved = cache.list(key)!!
            assertEquals(rows(30 downTo 1).map { it.id }, saved.rows.map { it.id })
            assertTrue(saved.hasOlder)
            assertEquals(1_000L, saved.savedAtMs)
        }

    @Test
    fun eachCombinationOfScopeGroupingAndFilterKeepsItsOwnPage() =
        runBlocking {
            val cache = cache()
            cache.saveList(key, rows(3 downTo 1), false)
            assertNull(cache.list(key.copy(threaded = false)))
            assertNull(cache.list(key.copy(unreadOnly = true)))
            assertNull(cache.list(key.copy(scope = ListScope.Unified("u1", "Inboxes"))))
        }

    @Test
    fun aListKeepsTwoPagesAndSaysThereIsMore() =
        runBlocking {
            val cache = cache()
            cache.saveList(key, rows(150 downTo 1), hasOlder = false)
            val saved = cache.list(key)!!
            assertEquals(100, saved.rows.size)
            assertTrue("rows were cut, so there are older ones", saved.hasOlder)
        }

    @Test
    fun theLeastRecentlyUsedListsGoFirst() =
        runBlocking {
            val cache = cache(FileOfflineCache.Budget(lists = 2))
            val a = key.copy(scope = ListScope.Folder("a1", "a"))
            val b = key.copy(scope = ListScope.Folder("a1", "b"))
            val c = key.copy(scope = ListScope.Folder("a1", "c"))
            cache.saveList(a, rows(1 downTo 1), false)
            cache.saveList(b, rows(1 downTo 1), false)
            assertNotNull("reading a refreshes it", cache.list(a))
            cache.saveList(c, rows(1 downTo 1), false)
            assertNotNull(cache.list(a))
            assertNull(cache.list(b))
            assertNotNull(cache.list(c))
        }

    @Test
    fun aConversationRoundTrips() =
        runBlocking {
            val cache = cache()
            cache.saveConversation("m1", ThreadResponse(listOf(detail(1), detail(2))))
            val saved = cache.conversation("m1")!!
            assertEquals(listOf("m1", "m2"), saved.thread.messages.map { it.id })
            assertEquals("Body 2", saved.thread.messages[1].bodyText)
            assertNull(cache.conversation("m9"))
        }

    @Test
    fun aResourceKeepsItsBytesAndMimeType() =
        runBlocking {
            val cache = cache()
            val bytes = byteArrayOf(0, 10, 13, -1, 42)
            cache.saveResource("attachment-m1-x", bytes, "image/png")
            val saved = cache.resource("attachment-m1-x")!!
            assertArrayEquals(bytes, saved.data)
            assertEquals("image/png", saved.mimeType)
        }

    @Test
    fun aKeyCannotEscapeTheCacheDirectory() =
        runBlocking {
            val cache = cache()
            cache.saveResource("../../escape", byteArrayOf(1), "x/y")
            assertFalse(File(dir.parentFile, "escape").exists())
            assertNotNull(cache.resource("../../escape"))
        }

    @Test
    fun aCorruptFileIsDeletedAndReadsAsAbsent() =
        runBlocking {
            val cache = cache()
            cache.saveList(key, rows(3 downTo 1), false)
            val file = File(dir, "lists").listFiles()!!.single()
            file.writeText("{ not json")
            assertNull(cache.list(key))
            assertFalse(file.exists())
        }

    @Test
    fun clearingLeavesNothingOfTheAccountBehind() =
        runBlocking {
            val cache = cache()
            cache.saveList(key, rows(3 downTo 1), false)
            cache.saveConversation("m1", ThreadResponse(listOf(detail(1))))
            cache.saveResource("r", byteArrayOf(1), "a/b")
            cache.clear()
            assertFalse(dir.exists())
            assertNull(cache.list(key))
            // The cache is usable again after a clear.
            cache.saveList(key, listOf(message(1)), false)
            assertNotNull(cache.list(key))
        }
}
