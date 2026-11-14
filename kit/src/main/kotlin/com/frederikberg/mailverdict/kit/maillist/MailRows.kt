package com.frederikberg.mailverdict.kit.maillist

import com.frederikberg.mailverdict.contract.MessageSummary
import com.frederikberg.mailverdict.kit.format.MailFormat
import java.time.Instant
import java.time.ZoneId

/**
 * Everything one list row draws, computed before layout. Every text is already bounded (see
 * [MailRowFactory]) so a row can never be taller than the fixed height the list gives it, and the
 * view does no work of its own beyond placing these strings.
 */
data class MailRowData(
    val id: String,
    val threadId: String,
    val accountId: String,
    val folderId: String,
    /** The sender, or the recipients in a folder holding mail the person sent. */
    val sender: String,
    val date: String,
    val subject: String,
    val snippet: String,
    val isUnread: Boolean,
    val isFlagged: Boolean,
    val isAnswered: Boolean,
    val isDraft: Boolean,
    /** The spam filter classified it as spam. */
    val isSpam: Boolean,
    val hasAttachments: Boolean,
    val isGlacier: Boolean,
    /** Messages in the conversation this row stands for; 1 for a lone message or an ungrouped list. */
    val threadCount: Int,
    val unreadInThread: Int,
)

/** Turns server rows into [MailRowData]. The clock and zone are parameters so a test can pin "today". */
class MailRowFactory(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = Instant::now,
) {
    fun make(
        message: MessageSummary,
        showRecipients: Boolean = false,
    ): MailRowData {
        val clock = now()
        val unread = !message.isSeen
        val threadCount = message.threadCount ?: 1
        return MailRowData(
            id = message.id,
            threadId = message.threadId,
            accountId = message.accountId,
            folderId = message.folderId,
            sender =
                bounded(
                    if (showRecipients) recipients(message) else MailFormat.senderName(message.fromAddr),
                    SENDER_MAX,
                ),
            date = MailFormat.listDate(message.receivedAt, clock, zone),
            subject =
                bounded(
                    message.subject
                        ?.collapseWhitespace()
                        .orEmpty()
                        .ifBlank { NO_SUBJECT },
                    SUBJECT_MAX,
                ),
            snippet = bounded(message.snippet?.collapseWhitespace().orEmpty(), SNIPPET_MAX),
            isUnread = unread,
            isFlagged = message.isFlagged,
            isAnswered = message.isAnswered,
            isDraft = message.isDraft,
            isSpam = message.verdictIsSpam == true,
            hasAttachments = message.hasAttachments,
            isGlacier = message.isGlacier,
            threadCount = threadCount,
            unreadInThread = message.unreadInThread ?: if (unread) 1 else 0,
        )
    }

    private fun recipients(message: MessageSummary): String {
        val names =
            message.toAddrs
                ?.addresses
                .orEmpty()
                .map(MailFormat::senderName)
        return if (names.isEmpty()) "To: unknown" else "To: " + names.joinToString(", ")
    }

    companion object {
        const val NO_SUBJECT = "(no subject)"

        /**
         * Character bounds applied before the text reaches layout. They are far past what the row's
         * line limits can draw, so the view's own ellipsis is what the reader sees; the text is cut
         * here so a 20,000-character subject never has to be laid out to be clipped. A cut text ends in
         * an ellipsis of its own, so a bound that bit is never invisible.
         */
        const val SENDER_MAX = 100
        const val SUBJECT_MAX = 200
        const val SNIPPET_MAX = 300

        internal fun bounded(
            text: String,
            max: Int,
        ): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

        private fun String.collapseWhitespace(): String = trim().replace(WHITESPACE, " ")

        private val WHITESPACE = Regex("\\s+")
    }
}

/** Which ends of the loaded rows the reader is close enough to for a page to be worth fetching. */
data class PagingNeeds(
    val older: Boolean,
    val newer: Boolean,
) {
    companion object {
        /** Pages load this many screens of rows before the edge, never at it. */
        const val SCREENS_AHEAD = 2

        /**
         * [firstVisible] and [lastVisible] are indexes into [total] rows, [visibleCount] the rows one
         * screen holds. With nothing laid out yet there is nothing to page toward.
         */
        fun of(
            firstVisible: Int,
            lastVisible: Int,
            visibleCount: Int,
            total: Int,
        ): PagingNeeds {
            if (total == 0 || visibleCount <= 0 || lastVisible < 0) return PagingNeeds(older = false, newer = false)
            val margin = SCREENS_AHEAD * visibleCount
            return PagingNeeds(older = total - 1 - lastVisible < margin, newer = firstVisible < margin)
        }
    }
}
