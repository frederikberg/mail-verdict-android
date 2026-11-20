package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.contract.MessageDetail
import com.frederikberg.mailverdict.kit.format.MailFormat
import java.time.ZoneId

enum class ReplyMode { Reply, ReplyAll }

data class ReplyDraft(
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    /** The `> `-prefixed plain-text quote appended to the authored text to build `body_text`. */
    val quotedText: String,
    /** "On <date>, <name> wrote:", shown above the quote and the first line of [quotedText], so the two agree. */
    val attribution: String,
    val inReplyTo: String?,
    val references: List<String>?,
)

data class ForwardDraft(
    val subject: String,
    val quotedText: String,
    val attribution: String,
)

/**
 * Recipients, subject, threading headers and both halves of a quote for reply, reply all and
 * forward, the same derivation as the web's `lib/reply.ts`.
 */
object ComposeReply {
    /**
     * [ownAddresses] are the account's login address and every identity it sends as. They are left
     * out of a reply-all's Cc. A message sent from one of them (a mail in Sent, one's own message in
     * a conversation) is answered to whoever it was sent to; only a mail addressed to nobody else
     * falls back to its sender.
     */
    fun reply(
        source: MessageDetail,
        ownAddresses: List<String>,
        mode: ReplyMode,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReplyDraft {
        val own = ownAddresses.map { ComposeAddresses.email(it).lowercase() }.toSet()
        // RFC 5322: a reply goes to Reply-To when the message names one, otherwise to From.
        val senderEmail = ComposeAddresses.email(source.fromAddr)
        val replyTo =
            ComposeAddresses
                .splitHeader(
                    source.replyTo,
                ).map(ComposeAddresses::email)
                .filter { it.isNotEmpty() }
        val toAddrs = source.toAddrs?.addresses.orEmpty()
        val ownRecipients =
            if (senderEmail.isNotEmpty() && senderEmail.lowercase() in own) {
                ComposeAddresses.dedupe(toAddrs.map(ComposeAddresses::email), own)
            } else {
                emptyList()
            }
        val to =
            ownRecipients.ifEmpty {
                ComposeAddresses.dedupe(replyTo.ifEmpty { listOfNotNull(senderEmail.ifEmpty { null }) })
            }

        val cc =
            if (mode == ReplyMode.ReplyAll) {
                val others = (toAddrs + source.ccAddrs?.addresses.orEmpty()).map(ComposeAddresses::email)
                ComposeAddresses.dedupe(others, own + to.map { it.lowercase() })
            } else {
                emptyList()
            }

        val references = source.references.orEmpty() + listOfNotNull(source.messageId)
        val attribution = "On ${MailFormat.fullDate(
            source.receivedAt,
            zone,
        )}, ${MailFormat.senderName(source.fromAddr)} wrote:"
        return ReplyDraft(
            to = to,
            cc = cc,
            subject = prefixed(source.subject, Regex("^re:", RegexOption.IGNORE_CASE), "Re: "),
            quotedText = quotedPlainText(source.bodyText, attribution),
            attribution = attribution,
            inReplyTo = source.messageId,
            references = references.ifEmpty { null },
        )
    }

    /** No In-Reply-To or References: a forward goes to someone new and starts a conversation of its own. */
    fun forward(
        source: MessageDetail,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ForwardDraft {
        val attribution =
            listOf(
                "---------- Forwarded message ----------",
                "From: ${source.fromAddr ?: "unknown"}",
                "Date: ${MailFormat.fullDate(source.receivedAt, zone)}",
                "Subject: ${source.subject ?: NO_SUBJECT}",
                "To: ${source.toAddrs?.addresses.orEmpty().joinToString(", ")}",
            ).joinToString("\n")
        return ForwardDraft(
            subject = prefixed(source.subject, Regex("^fwd?:", RegexOption.IGNORE_CASE), "Fwd: "),
            quotedText = quotedPlainText(source.bodyText, attribution),
            attribution = attribution,
        )
    }

    /**
     * The plain-text quote of a reopened draft. There is no original to build it from, only the
     * draft's own `body_text`, where the attribution line marks where the quote began when it was saved.
     */
    fun draftQuotedText(
        bodyText: String?,
        attribution: String,
    ): String {
        if (bodyText == null || attribution.isEmpty()) return ""
        val index = bodyText.indexOf("\n\n$attribution")
        return if (index >= 0) bodyText.substring(index) else ""
    }

    /** The plain-text quote rebuilt from a quote's HTML: a cancelled send keeps its HTML body, not its text one. */
    fun quotedPlainText(
        quote: ComposeQuote,
    ): String = quotedPlainText(ComposeHtml.toText(quote.html), quote.attribution)

    fun quotedPlainText(
        body: String?,
        attribution: String,
    ): String = "\n\n$attribution\n" + body.orEmpty().split("\n").joinToString("\n") { "> $it" }

    private fun prefixed(
        subject: String?,
        existing: Regex,
        prefix: String,
    ): String {
        val base = subject ?: NO_SUBJECT
        return if (existing.containsMatchIn(base)) base else prefix + base
    }

    private const val NO_SUBJECT = "(no subject)"
}
