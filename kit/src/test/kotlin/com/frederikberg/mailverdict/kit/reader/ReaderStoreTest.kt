package com.frederikberg.mailverdict.kit.reader

import com.frederikberg.mailverdict.contract.AddressList
import com.frederikberg.mailverdict.contract.AttachmentSummary
import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.contract.ThreadResponse
import com.frederikberg.mailverdict.kit.actions.ActionQueue
import com.frederikberg.mailverdict.kit.actions.FakeTransport
import com.frederikberg.mailverdict.kit.actions.InMemoryIntentPersistence
import com.frederikberg.mailverdict.kit.actions.IntentPhase
import com.frederikberg.mailverdict.kit.actions.UiAction
import com.frederikberg.mailverdict.kit.cache.FileOfflineCache
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        scope.cancel()
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
        var failure: Exception? = null
        val feedback = mutableListOf<Triple<String, String, Boolean>>()
        var roles = mapOf("f1" to "inbox", "trash1" to "trash", "junk1" to "junk")

        override suspend fun thread(messageId: String): ThreadResponse {
            failure?.let { throw it }
            return ThreadResponse(thread)
        }

        override suspend fun messageWithImages(messageId: String) =
            thread
                .first {
                    it.id == messageId
                }.copy(hasBlockedImages = false, bodyHtml = "<img src=\"x\">")

        override suspend fun folderRoles(accountId: String) = roles

        override suspend fun sendFeedback(
            accountId: String,
            messageId: String,
            isSpam: Boolean,
        ) {
            feedback += Triple(accountId, messageId, isSpam)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = FakeTransport()
    private val queue = ActionQueue(transport, InMemoryIntentPersistence(), scope)

    /** What the queue sent, in order, as "action:id". */
    private fun sentActions(): List<String> {
        runBlocking { withTimeout(5_000) { queue.waitUntilIdle() } }
        return transport.sent.flatMap { sent -> sent.ids.map { "${sent.action}:$it" } }
    }

    private fun store(
        backend: Backend,
        opened: String = "m2",
        folders: Set<String> = setOf("f1"),
        cache: FileOfflineCache = FileOfflineCache(dir),
    ) = ReaderStore(opened, folders, backend, cache, actions = queue, scope = scope)

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
            assertEquals(listOf("mark_read:m2", "mark_read:m1"), sentActions())
        }

    @Test
    fun aDraftIsNeverMarkedReadByOpeningIt() =
        runBlocking {
            val backend = Backend(listOf(detail(2, draft = true)))
            store(backend).load()
            assertTrue(sentActions().isEmpty())
        }

    @Test
    fun aFailureToMarkReadDoesNotFailTheReaderAndTheQueueKeepsTrying() =
        runBlocking {
            transport.onMessage = { throw ApiError.Transport("no route") }
            val store = store(Backend(listOf(detail(2))))
            store.load()
            assertEquals(ReaderPhase.Loaded, store.state.value.phase)
            withTimeout(5_000) { while (queue.state.value.intents.single().lastError == null) kotlinx.coroutines.delay(5) }
            assertEquals("still wanted by the queue, not failed", IntentPhase.Pending, queue.state.value.intents.single().state)
        }

    @Test
    fun anUnreachableServerShowsTheConversationSavedWhenItWasLastOpenedAndDoesNotMarkAnythingRead() =
        runBlocking {
            val backend = Backend(listOf(detail(1), detail(2)))
            val cache = FileOfflineCache(dir)
            store(backend, cache = cache).load()
            sentActions()
            transport.sent.clear()

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
            assertTrue(sentActions().isEmpty())
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

    @Test
    fun aMessageMarkedUnreadWhileLookingAtItIsNotMarkedReadAgain() =
        runBlocking {
            val store = store(Backend(listOf(detail(1, seen = true), detail(2))))
            store.load()
            assertEquals(listOf("mark_read:m2"), sentActions())
            store.perform(UiAction.MarkUnread, "m2")
            assertEquals(listOf("mark_read:m2", "mark_unread:m2"), sentActions())
            transport.sent.clear()
            store.load()
            assertTrue(
                "m2 stays unread on screen",
                store.state.value.messages
                    .first { it.id == "m2" }
                    .isUnread,
            )
            assertTrue("and is not marked read again", sentActions().isEmpty())
        }

    @Test
    fun actionsOnTheOpenedMessageGoThroughTheQueueGuardedByItsFolder() =
        runBlocking {
            val store = store(Backend(listOf(detail(2))))
            store.load()
            sentActions()
            transport.sent.clear()
            assertTrue("archive leaves the folder", store.perform(UiAction.Archive, "m2"))
            assertTrue("star does not", !store.perform(UiAction.Star, "m2"))
            sentActions()
            assertEquals(mapOf("m2" to "f1"), transport.sent.first { it.action == "archive" }.expected)
        }

    @Test
    fun theReaderShowsAStarAtOnceThroughTheQueue() =
        runBlocking {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            transport.onMessage = {
                gate.await()
                transport.ok(it)
            }
            val store = store(Backend(listOf(detail(2, seen = true))))
            store.load()
            store.perform(UiAction.Star, "m2")
            withTimeout(5_000) {
                while (!store.state.value.messages
                        .first()
                        .isFlagged
                ) {
                    kotlinx.coroutines.delay(5)
                }
            }
            gate.complete(Unit)
            Unit
        }

    @Test
    fun theMenuIsToldTheFolderRoleAndTheVerdict() =
        runBlocking {
            val store = store(Backend(listOf(detail(2, folder = "trash1"))))
            store.load()
            val context = store.actionContext("m2")!!
            assertTrue(context.isInTrash)
            assertEquals(null, context.verdictIsSpam)
        }

    @Test
    fun aVerdictIsConfirmedOrCorrectedThroughTheFeedbackRoute() =
        runBlocking {
            val verdict =
                com.frederikberg.mailverdict.contract.VerdictResponse(
                    "v",
                    "m2",
                    true,
                    source = "model",
                    createdAt = "2026-11-01T00:00:00+00:00",
                )
            val backend = Backend(listOf(detail(2).copy(verdict = verdict)))
            val store = store(backend)
            store.load()
            store.sendVerdictFeedback("m2", confirming = true)
            store.sendVerdictFeedback("m2", confirming = false)
            assertEquals(listOf(true, false), backend.feedback.map { it.third })
        }
}
