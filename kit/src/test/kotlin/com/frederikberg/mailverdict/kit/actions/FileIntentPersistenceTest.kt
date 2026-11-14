package com.frederikberg.mailverdict.kit.actions

import com.frederikberg.mailverdict.kit.maillist.message
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileIntentPersistenceTest {
    private val dir = Files.createTempDirectory("intents").toFile()
    private val file = File(dir, "actions/q.json")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun intent(id: String) =
        MailIntent(
            id = id,
            accountId = "a1",
            action = TriageAction.Move,
            targetFolderId = "f2",
            messageIds = listOf("m1"),
            delivery = Delivery.Bulk(true),
            originFolderIds = mapOf("m1" to "f1"),
            snapshots = listOf(message(1)),
            createdAtMs = 5,
            state = IntentPhase.Failed,
            attempts = 3,
            lastError = "boom",
            reversedAction = TriageAction.Archive,
        )

    @Test
    fun intentsRoundTripWithEverythingAnUndoNeeds() {
        val persistence = FileIntentPersistence(file)
        persistence.save(listOf(intent("a"), intent("b")))
        assertEquals(listOf(intent("a"), intent("b")), FileIntentPersistence(file).load())
    }

    @Test
    fun aRecordThatNoLongerDecodesIsDroppedAndTheRestKept() {
        FileIntentPersistence(file).save(listOf(intent("a")))
        val json = file.readText().trimEnd().removeSuffix("]") + ",{\"id\":\"broken\",\"action\":\"teleport\"}]"
        file.writeText(json)
        assertEquals(listOf("a"), FileIntentPersistence(file).load().map { it.id })
    }

    @Test
    fun aMissingOrCorruptFileIsAnEmptyQueue() {
        assertTrue(FileIntentPersistence(file).load().isEmpty())
        file.parentFile.mkdirs()
        file.writeText("not json")
        assertTrue(FileIntentPersistence(file).load().isEmpty())
    }
}
