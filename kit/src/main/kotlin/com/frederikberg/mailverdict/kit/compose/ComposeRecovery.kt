package com.frederikberg.mailverdict.kit.compose

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** What a composer's recovery snapshot holds: every authored field, files included, since the system ends a background app without warning. */
class RecoveryContent(
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String,
    val body: String,
    val quote: ComposeQuote?,
    val attachments: List<ComposeAttachment>,
)

/** Where unsent work survives the process; null in a run that keeps nothing. */
interface ComposeRecovery {
    fun read(key: String): RecoveryContent?

    fun write(
        key: String,
        content: RecoveryContent,
    )

    fun clear(key: String)

    companion object {
        /** Which composer this is: a draft being edited, a reply, or a fresh message (one slot, since one composer is open at a time). */
        fun key(
            replacesMessageId: String?,
            inReplyTo: String?,
        ): String =
            when {
                replacesMessageId != null -> "draft:$replacesMessageId"
                !inReplyTo.isNullOrEmpty() -> "reply:$inReplyTo"
                else -> "new"
            }
    }
}

/**
 * One directory per composer key: a JSON manifest and one file per attachment. An attachment file is
 * written once and kept while it stays in the snapshot, so the once-a-second rewrite while typing
 * costs the manifest only.
 */
class FileComposeRecovery(
    private val root: File,
) : ComposeRecovery {
    @Serializable
    private data class Manifest(
        val to: List<String>,
        val cc: List<String>,
        val bcc: List<String>,
        val subject: String,
        val body: String,
        val quote: ComposeQuote? = null,
        val attachments: List<FileEntry> = emptyList(),
    )

    @Serializable
    private data class FileEntry(
        val id: String,
        val filename: String,
        val contentType: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun read(key: String): RecoveryContent? {
        val folder = folder(key)
        val manifest =
            runCatching { json.decodeFromString(Manifest.serializer(), File(folder, MANIFEST).readText()) }
                .getOrNull() ?: return null
        val attachments =
            manifest.attachments.mapNotNull { entry ->
                val file = File(folder, "attachment-${entry.id}")
                if (file.exists()) {
                    ComposeAttachment(
                        entry.filename,
                        entry.contentType,
                        file.readBytes(),
                        entry.id,
                    )
                } else {
                    null
                }
            }
        return RecoveryContent(
            manifest.to,
            manifest.cc,
            manifest.bcc,
            manifest.subject,
            manifest.body,
            manifest.quote,
            attachments,
        )
    }

    @Synchronized
    override fun write(
        key: String,
        content: RecoveryContent,
    ) {
        val folder = folder(key).apply { mkdirs() }
        val wanted = mutableSetOf(MANIFEST)
        content.attachments.forEach {
            val name = "attachment-${it.id}"
            wanted += name
            val file = File(folder, name)
            if (!file.exists()) file.writeBytes(it.data)
        }
        val manifest =
            Manifest(
                content.to,
                content.cc,
                content.bcc,
                content.subject,
                content.body,
                content.quote,
                content.attachments.map { FileEntry(it.id, it.filename, it.contentType) },
            )
        val temporary = File(folder, "$MANIFEST.tmp")
        temporary.writeText(json.encodeToString(Manifest.serializer(), manifest))
        temporary.renameTo(File(folder, MANIFEST))
        folder.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() }
    }

    @Synchronized
    override fun clear(key: String) {
        folder(key).deleteRecursively()
    }

    private fun folder(key: String): File =
        File(
            root,
            MessageDigest
                .getInstance("SHA-256")
                .digest(key.toByteArray())
                .take(12)
                .joinToString("") { "%02x".format(it) },
        )

    private companion object {
        const val MANIFEST = "snapshot.json"
    }
}
