package com.frederikberg.mailverdict.kit.reader

import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.kit.format.MailFormat
import java.time.Instant

/** What a message shows in place of a body. */
sealed interface ReaderBody {
    /** Server-sanitized HTML, drawn in a web view. */
    data class Html(
        val html: String,
    ) : ReaderBody

    /** [body] is the bounded start of [raw], what is drawn until the person asks for the rest. */
    data class Text(
        val body: PlainBody,
        val raw: String,
    ) : ReaderBody

    data object Empty : ReaderBody
}

data class ReaderAttachment(
    val id: String,
    val filename: String,
    val contentType: String,
    val size: String,
)

/** One message of the conversation, with every text already formatted for drawing. */
data class ReaderMessage(
    val id: String,
    val senderName: String,
    val senderEmail: String,
    /** "to Alex, Sam" */
    val recipients: String,
    val cc: String?,
    /** The header's date: what the list shows. */
    val date: String,
    val fullDate: String,
    /** One line shown while the message is collapsed. */
    val preview: String,
    val body: ReaderBody,
    val attachments: List<ReaderAttachment>,
    val isUnread: Boolean,
    val isFlagged: Boolean,
    /** The folder the message is in. */
    val folderId: String,
    val isDraft: Boolean,
    val isGlacier: Boolean,
    val glacierOrigin: String?,
    /** The spam filter's verdict, when it classified the message. */
    val isSpam: Boolean?,
    /** The server held back remote images the person has not asked to see. */
    val hasBlockedImages: Boolean,
    val imagesLoaded: Boolean,
)

sealed interface ReaderPhase {
    data object Loading : ReaderPhase

    data object Loaded : ReaderPhase

    data class Failed(
        val message: String,
    ) : ReaderPhase
}

data class ReaderState(
    val phase: ReaderPhase = ReaderPhase.Loading,
    val subject: String = "",
    /** Newest first. */
    val messages: List<ReaderMessage> = emptyList(),
    val openedId: String,
    val expandedIds: Set<String> = emptySet(),
    /** Bodies shown in full although they were cut for drawing. */
    val fullBodyIds: Set<String> = emptySet(),
    /** Set while the conversation on screen comes from the offline cache. */
    val savedCopyAtMs: Long? = null,
    /** A one-off message (an attachment that would not download); cleared by [ReaderStore.dismissNotice]. */
    val notice: String? = null,
)

object ReaderMessages {
    private const val PREVIEW_MAX = 160

    fun make(
        message: MessageDetail,
        imagesLoaded: Boolean = false,
        now: Instant = Instant.now(),
    ): ReaderMessage {
        val html = message.bodyHtml?.takeIf { it.isNotBlank() }
        val text = message.bodyText?.takeIf { it.isNotBlank() }
        val body =
            when {
                html != null -> ReaderBody.Html(html)
                text != null -> ReaderBody.Text(PlainText.prepare(text), text)
                else -> ReaderBody.Empty
            }
        val to =
            message.toAddrs
                ?.addresses
                .orEmpty()
                .map(MailFormat::senderName)
        val cc =
            message.ccAddrs
                ?.addresses
                .orEmpty()
                .map(MailFormat::senderName)
        return ReaderMessage(
            id = message.id,
            senderName = MailFormat.senderName(message.fromAddr),
            senderEmail = MailFormat.email(message.fromAddr),
            recipients = if (to.isEmpty()) "" else "to " + to.joinToString(", "),
            cc = cc.takeIf { it.isNotEmpty() }?.joinToString(", "),
            date = MailFormat.listDate(message.receivedAt ?: message.createdAt, now),
            fullDate = MailFormat.fullDate(message.receivedAt ?: message.createdAt),
            preview =
                (message.snippet ?: text.orEmpty())
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(PREVIEW_MAX),
            body = body,
            attachments =
                message.attachments.map {
                    ReaderAttachment(
                        id = it.id,
                        filename = it.filename?.takeIf(String::isNotBlank) ?: "attachment",
                        contentType = it.contentType ?: "application/octet-stream",
                        size = MailFormat.size(it.sizeBytes),
                    )
                },
            isUnread = !message.isSeen,
            isFlagged = message.isFlagged,
            folderId = message.folderId,
            isDraft = message.isDraft,
            isGlacier = message.isGlacier,
            glacierOrigin = message.originFolderName,
            isSpam = message.verdict?.isSpam,
            hasBlockedImages = message.hasBlockedImages && !imagesLoaded,
            imagesLoaded = imagesLoaded || message.imagesAllowed,
        )
    }
}
