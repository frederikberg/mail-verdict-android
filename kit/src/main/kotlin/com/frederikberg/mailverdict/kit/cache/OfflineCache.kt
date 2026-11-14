package com.frederikberg.mailverdict.kit.cache

import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.contract.MessageSummary
import com.frederikberg.mailverdict.contract.ThreadResponse
import com.frederikberg.mailverdict.kit.maillist.ListScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Which list a saved page belongs to. Grouped and ungrouped rows are different ids and the unread filter is a different list, so each combination keeps its own page. */
data class OfflineListKey(
    val scope: ListScope,
    val threaded: Boolean,
    val unreadOnly: Boolean,
) {
    val fileName: String
        get() = "${scope.key}-${if (threaded) "threaded" else "flat"}-${if (unreadOnly) "unread" else "all"}.json"
}

/** A list page as it was last read from the server. */
data class SavedList(
    val rows: List<MessageSummary>,
    val hasOlder: Boolean,
    val savedAtMs: Long,
)

/** A conversation as the reader last read it. */
data class SavedConversation(
    val thread: ThreadResponse,
    val savedAtMs: Long,
)

/** An inline image a saved conversation showed. */
class SavedResource(
    val data: ByteArray,
    val mimeType: String,
)

/**
 * Mail kept on the device so it can still be read when the server cannot be reached: the newest page
 * of each list visited recently and each conversation opened recently, with the inline images they
 * showed. Nothing reads it while the server answers; it is the fallback a failed read turns to.
 */
interface OfflineCache {
    suspend fun saveList(
        key: OfflineListKey,
        rows: List<MessageSummary>,
        hasOlder: Boolean,
    )

    suspend fun list(key: OfflineListKey): SavedList?

    suspend fun saveConversation(
        openedMessageId: String,
        thread: ThreadResponse,
    )

    suspend fun conversation(openedMessageId: String): SavedConversation?

    suspend fun saveResource(
        key: String,
        data: ByteArray,
        mimeType: String,
    )

    suspend fun resource(key: String): SavedResource?

    /** Forgets everything; nothing of a signed-out account stays on the device. */
    suspend fun clear()
}

/** A cache that keeps nothing: fixture runs and tests that do not exercise offline reading. */
object NoOfflineCache : OfflineCache {
    override suspend fun saveList(
        key: OfflineListKey,
        rows: List<MessageSummary>,
        hasOlder: Boolean,
    ) = Unit

    override suspend fun list(key: OfflineListKey): SavedList? = null

    override suspend fun saveConversation(
        openedMessageId: String,
        thread: ThreadResponse,
    ) = Unit

    override suspend fun conversation(openedMessageId: String): SavedConversation? = null

    override suspend fun saveResource(
        key: String,
        data: ByteArray,
        mimeType: String,
    ) = Unit

    override suspend fun resource(key: String): SavedResource? = null

    override suspend fun clear() = Unit
}

/**
 * One JSON file per list and per conversation under [directory] (one directory per server), a
 * binary file per resource. Each kind is bounded by [Budget] and evicted least recently used first by
 * the file's modification time, which every save and every read sets. A file that cannot be read
 * (truncated, or written by another version) is deleted rather than trusted: this is a cache, and the
 * next read from the server writes it again.
 */
class FileOfflineCache(
    private val directory: File,
    private val budget: Budget = Budget(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OfflineCache {
    data class Budget(
        val lists: Int = 30,
        /** Rows kept per list: two pages, so a list opened offline still scrolls a little. */
        val rowsPerList: Int = 100,
        val conversations: Int = 100,
        val resources: Int = 300,
        val resourceBytes: Long = 50L * 1024 * 1024,
    )

    @Serializable
    private class ListFile(
        val version: Int,
        val savedAtMs: Long,
        val hasOlder: Boolean,
        val rows: List<MessageSummary>,
    )

    @Serializable
    private class ConversationFile(
        val version: Int,
        val savedAtMs: Long,
        val thread: ThreadResponse,
    )

    private val lock = Mutex()
    private val lists = File(directory, "lists")
    private val conversations = File(directory, "conversations")
    private val resources = File(directory, "resources")

    override suspend fun saveList(
        key: OfflineListKey,
        rows: List<MessageSummary>,
        hasOlder: Boolean,
    ) = io {
        val kept = rows.take(budget.rowsPerList)
        val file = ListFile(VERSION, nowMs(), hasOlder || rows.size > kept.size, kept)
        write(File(lists, key.fileName), ContractJson.encodeToString(ListFile.serializer(), file).toByteArray())
        prune(lists, budget.lists)
    }

    override suspend fun list(key: OfflineListKey): SavedList? =
        io {
            readJson(File(lists, key.fileName)) { text ->
                ContractJson.decodeFromString(ListFile.serializer(), text).takeIf { it.version == VERSION }?.let {
                    SavedList(it.rows, it.hasOlder, it.savedAtMs)
                }
            }
        }

    override suspend fun saveConversation(
        openedMessageId: String,
        thread: ThreadResponse,
    ) = io {
        val file = ConversationFile(VERSION, nowMs(), thread)
        write(
            File(conversations, safeName(openedMessageId) + ".json"),
            ContractJson.encodeToString(ConversationFile.serializer(), file).toByteArray(),
        )
        prune(conversations, budget.conversations)
    }

    override suspend fun conversation(openedMessageId: String): SavedConversation? =
        io {
            readJson(File(conversations, safeName(openedMessageId) + ".json")) { text ->
                ContractJson
                    .decodeFromString(
                        ConversationFile.serializer(),
                        text,
                    ).takeIf { it.version == VERSION }
                    ?.let {
                        SavedConversation(it.thread, it.savedAtMs)
                    }
            }
        }

    override suspend fun saveResource(
        key: String,
        data: ByteArray,
        mimeType: String,
    ) = io {
        // The mime type is the first line, the bytes follow.
        write(File(resources, safeName(key)), mimeType.toByteArray() + '\n'.code.toByte() + data)
        prune(resources, budget.resources, budget.resourceBytes)
    }

    override suspend fun resource(key: String): SavedResource? =
        io {
            val file = File(resources, safeName(key))
            if (!file.isFile) return@io null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@io null
            val split = bytes.indexOf('\n'.code.toByte())
            if (split < 0) {
                file.delete()
                return@io null
            }
            file.setLastModified(nowMs())
            SavedResource(bytes.copyOfRange(split + 1, bytes.size), String(bytes, 0, split))
        }

    override suspend fun clear() =
        io {
            directory.deleteRecursively()
            Unit
        }

    private suspend fun <T> io(block: () -> T): T =
        withContext(Dispatchers.IO) { lock.withLock { block() } }

    private fun <T : Any> readJson(
        file: File,
        decode: (String) -> T?,
    ): T? {
        if (!file.isFile) return null
        val decoded = runCatching { decode(file.readText()) }.getOrNull()
        if (decoded == null) {
            file.delete()
            return null
        }
        file.setLastModified(nowMs())
        return decoded
    }

    /** Through a temporary file and an atomic move, so a reader never sees half a file. */
    private fun write(
        target: File,
        bytes: ByteArray,
    ) {
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        target.setLastModified(nowMs())
    }

    private fun prune(
        dir: File,
        maxFiles: Int,
        maxBytes: Long = Long.MAX_VALUE,
    ) {
        val files =
            dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.sortedByDescending { it.lastModified() }
                ?: return
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= maxFiles || bytes > maxBytes) file.delete()
        }
    }

    /** Ids and keys become file names: anything outside a small safe set is replaced. */
    private fun safeName(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val VERSION = 1
    }
}
