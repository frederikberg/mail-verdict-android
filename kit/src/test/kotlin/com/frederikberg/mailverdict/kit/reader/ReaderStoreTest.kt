package com.frederikberg.mailverdict.kit.reader

import com.frederikberg.mailverdict.contract.AddressList
import com.frederikberg.mailverdict.contract.AttachmentSummary
import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.contract.ThreadResponse
import com.frederikberg.mailverdict.kit.cache.FileOfflineCache
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReaderStoreTest {
    private val dir = Files.createTempDirectory("reader-store").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun detail(
        n: Int,
        seen: Boolean = false,
        folder: String = "f1",
        html: String? = null,
        text: String? = "Body $n",
        draft: Boolean = false,
    ) = MessageDetail(
        id = "m$n",
        accountId = "a1",
        folderId = folder,
        threadId = "t1",
        createdAt = "2026-11-01T0$n:00:00+00:00",
        receivedAt = "2026-11-01T0$n:00:00+00:00",
        subject = "Plans",
        fromAddr = "Ada $n <ada$n@example.com>",
        toAddrs = AddressList(listOf("me@example.com")),
        bodyText = text,
        bodyHtml = html,
        isSeen = seen,
        isDraft = draft,
        attachments =
            if (n ==
                2
            ) {
                listOf(AttachmentSummary("att1", "plan.pdf", "application/pdf", 2048))
            } else {
                emptyList()
            },
        hasBlockedImages = html != null,
    )

    private class Backend(
        var thread: List<MessageDetail>,
    ) : ReaderBackend {
        val marked = mutableListOf<String>()
        var failure: Exception? = null
        var markFailure: Exception? = null

        override suspend fun thread(messageId: String): ThreadResponse {
            failure?.let { throw it }
            return ThreadResponse(thread)
        }

        override suspend fun messageWithImages(messageId: String) =
            thread
                .first {
                    it.id == messageId
                }.copy(hasBlockedImages = false, bodyHtml = "<img src=\"x\">")

        override suspend fun markRead(messageId: String) {
            markFailure?.let { throw it }
            marked += messageId
        }
    }

    private fun store(
        backend: Backend,
        opened: String = "m2",
        folders: Set<String> = setOf("f1"),
        cache: FileOfflineCache = FileOfflineCache(dir),
    ) = ReaderStore(opened, folders, backend, cache)

    @Test
    fun theConversationIsNewestFirstWithOnlyTheOpenedMessageExpanded() =
        runBlocking {
            val store = store(Backend(listOf(detail(1), detail(2), detail(3))))
            store.load()
            val state = store.state.value
            assertEquals(ReaderPhase.Loaded, state.phase)
            assertEquals(listOf("m3", "m2", "m1"), state.messages.map { it.id })
            assertEquals(setOf("m2"), state.expandedIds)
            assertEquals("Plans", state.subject)
            assertEquals("Ada 2", state.messages[1].senderName)
            assertEquals("to me", state.messages[1].recipients)
            assertEquals(
                "plan.pdf",
                state.messages[1]
                    .attachments
                    .single()
                    .filename,
            )
            assertEquals(
                "2.0 KB",
                state.messages[1]
                    .attachments
                    .single()
                    .size,
            )
        }

    @Test
    fun readingMarksTheOpenedMessageAndTheRestOfTheConversationInItsFoldersRead() =
        runBlocking {
            val backend = Backend(listOf(detail(1), detail(2), detail(3, folder = "f9"), detail(4, seen = true)))
            store(backend).load()
            assertEquals(listOf("m2", "m1"), backend.marked)
        }

    @Test
    fun aDraftIsNeverMarkedReadByOpeningIt() =
        runBlocking {
            val backend = Backend(listOf(detail(2, draft = true)))
            store(backend).load()
            assertTrue(backend.marked.isEmpty())
        }

    @Test
    fun aFailureToMarkReadDoesNotFailTheReader() =
        runBlocking {
            val backend = Backend(listOf(detail(2))).also { it.markFailure = ApiError.Transport("no route") }
            val store = store(backend)
            store.load()
            assertEquals(ReaderPhase.Loaded, store.state.value.phase)
        }

    @Test
    fun anUnreachableServerShowsTheConversationSavedWhenItWasLastOpenedAndDoesNotMarkAnythingRead() =
        runBlocking {
            val backend = Backend(listOf(detail(1), detail(2)))
            val cache = FileOfflineCache(dir)
            store(backend, cache = cache).load()
            backend.marked.clear()

            backend.failure = ApiError.Transport("no route")
            val offline = store(backend, cache = cache)
            offline.load()
            assertEquals(ReaderPhase.Loaded, offline.state.value.phase)
            assertNotNull(offline.state.value.savedCopyAtMs)
            assertEquals(
                listOf("m2", "m1"),
                offline.state.value.messages
                    .map { it.id },
            )
            assertTrue(backend.marked.isEmpty())
        }

    @Test
    fun withNothingSavedAnOtherwiseFailedReadIsAFailure() =
        runBlocking {
            val backend = Backend(listOf(detail(2))).also { it.failure = ApiError.Detail("gone", 404) }
            val store = store(backend)
            store.load()
            assertEquals(ReaderPhase.Failed("gone"), store.state.value.phase)
            assertNull(store.state.value.savedCopyAtMs)
        }

    @Test
    fun aCollapsedMessageExpandsAndCollapsesAgain() =
        runBlocking {
            val store = store(Backend(listOf(detail(1), detail(2))))
            store.load()
            store.toggleExpanded("m1")
            assertEquals(setOf("m2", "m1"), store.state.value.expandedIds)
            store.toggleExpanded("m2")
            assertEquals(setOf("m1"), store.state.value.expandedIds)
        }

    @Test
    fun remoteImagesLoadForOneMessageOnly() =
        runBlocking {
            val store = store(Backend(listOf(detail(1, html = "<p>a</p>"), detail(2, html = "<p>b</p>"))))
            store.load()
            assertTrue(
                store.state.value.messages
                    .all { it.hasBlockedImages },
            )
            store.loadImages("m1")
            val byId =
                store.state.value.messages
                    .associateBy { it.id }
            assertFalse(byId.getValue("m1").hasBlockedImages)
            assertTrue(byId.getValue("m1").imagesLoaded)
            assertTrue(byId.getValue("m2").hasBlockedImages)
        }

    @Test
    fun aBodyIsHtmlTextOrNothing() =
        runBlocking {
            val store =
                store(Backend(listOf(detail(1, html = "<p>a</p>"), detail(2, text = "plain"), detail(3, text = null))))
            store.load()
            val bodies =
                store.state.value.messages
                    .associate { it.id to it.body }
            assertTrue(bodies.getValue("m1") is ReaderBody.Html)
            assertEquals("plain", (bodies.getValue("m2") as ReaderBody.Text).body.text)
            assertEquals(ReaderBody.Empty, bodies.getValue("m3"))
        }

    @Test
    fun readPolicyKeepsTheMessageSomeoneJustMarkedUnreadUnread() {
        assertFalse(ReaderReadPolicy.shouldMarkRead(detail(1), explicitlyUnreadId = "m1"))
        assertTrue(ReaderReadPolicy.shouldMarkRead(detail(1), explicitlyUnreadId = null))
        assertFalse(ReaderReadPolicy.shouldMarkRead(detail(1, seen = true), null))
    }
}
