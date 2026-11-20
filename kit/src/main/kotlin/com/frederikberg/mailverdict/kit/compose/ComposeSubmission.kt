package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.contract.OutboxCreateRequest
import com.frederikberg.mailverdict.kit.api.OutboxUpload

/** Everything one Send or Save Draft carries, gathered from the composer. */
data class SubmissionInput(
    val accountId: String,
    /** `send` or `draft`. */
    val kind: String,
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String,
    val body: String,
    /** The HTML of a body reopened unchanged: sent as it was, so formatting written elsewhere survives. */
    val authoredHtml: String?,
    val quote: ComposeQuote?,
    val quotedText: String,
    val inReplyTo: String?,
    val references: List<String>?,
    val identityId: String?,
    val replacesMessageId: String?,
    val attachments: List<ComposeAttachment>,
    val idempotencyKey: String,
)

object ComposeSubmission {
    /**
     * The `POST /outbox` body the web sends: `body_text` is the authored text with the plain-text
     * quote below it, `body_html` the authored paragraphs with the quote wrapper below them (the
     * server requires the text whenever the HTML is set).
     */
    fun build(input: SubmissionInput): Pair<OutboxCreateRequest, List<OutboxUpload>> {
        val authored = input.authoredHtml ?: ComposeHtml.fromText(input.body)
        val request =
            OutboxCreateRequest(
                accountId = input.accountId,
                kind = input.kind,
                to = input.to,
                cc = input.cc,
                bcc = input.bcc,
                subject = input.subject,
                bodyText = input.body.trimEnd() + input.quotedText,
                bodyHtml = ComposeHtml.body(authored, input.quote),
                inReplyTo = input.inReplyTo,
                references = input.references,
                identityId = input.identityId,
                replacesMessageId = input.replacesMessageId,
                idempotencyKey = input.idempotencyKey,
            )
        return request to input.attachments.map { OutboxUpload(it.filename, it.contentType, it.data) }
    }
}
