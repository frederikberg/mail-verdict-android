package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.IdentityResponse
import com.frederikberg.mailverdict.contract.PendingSendResponse
import com.frederikberg.mailverdict.kit.api.OutboxCreateResult
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

sealed interface ComposerPhase {
    data object Loading : ComposerPhase

    data object Editing : ComposerPhase

    /** A send is on its way; the form stays as it was underneath and comes back if it fails. */
    data object Sending : ComposerPhase

    data class LoadFailed(
        val message: String,
    ) : ComposerPhase
}

sealed interface SubmitOutcome {
    data object Sent : SubmitOutcome

    data object DraftSaved : SubmitOutcome

    /** Held inside the undo window: the undo-send capsule reports it, not a notice. */
    data class Pending(
        val send: PendingSendResponse,
    ) : SubmitOutcome

    data class Failed(
        val message: String,
    ) : SubmitOutcome

    /** Refused before anything was sent: no recipient, or text that is not an address. */
    data object Blocked : SubmitOutcome

    /** A second submit while one is in flight, or after one succeeded. */
    data object Ignored : SubmitOutcome
}

sealed interface LeaveOutcome {
    /** The composer may close; [notice] is what to tell the person, if anything. */
    data class Close(
        val notice: String?,
    ) : LeaveOutcome

    /** Saving the draft failed: the composer stays open with the reason on screen. */
    data class Stay(
        val message: String,
    ) : LeaveOutcome
}

data class ComposerState(
    val phase: ComposerPhase = ComposerPhase.Loading,
    val title: String = ComposerStore.NEW_TITLE,
    val recipients: Map<RecipientField, List<String>> = emptyMap(),
    /** "Not a valid email address: …" for text a field could not turn into a recipient. */
    val recipientNotes: Map<RecipientField, String> = emptyMap(),
    val showsCcBcc: Boolean = false,
    val fromOptions: List<FromAddress> = emptyList(),
    val selectedFrom: FromAddress? = null,
    val subject: String = "",
    val body: String = "",
    val quote: ComposeQuote? = null,
    /** The quoted original as text, for showing under the body. */
    val quotePreview: String = "",
    val attachments: List<ComposeAttachment> = emptyList(),
    /** Files a reopened composer should have carried and could not get back. */
    val unrestoredAttachments: List<String> = emptyList(),
    val submitError: String? = null,
    val sendHint: String? = null,
    /** A recovery snapshot of this same composer is on offer until restored or dismissed. */
    val recoverable: Boolean = false,
    val isDirty: Boolean = false,
    /** Bumped when the store replaces subject and body wholesale; a field reads the store again only then. */
    val textRevision: Int = 0,
    /** Bumped when the store changes a recipient field's typed text itself (a commit, a pick). */
    val recipientRevision: Int = 0,
) {
    fun recipients(field: RecipientField): List<String> = recipients[field].orEmpty()

    val showsFromPicker: Boolean get() = fromOptions.size > 1
}

/** A cancelled send given back to the composer that reopens it, files sorted into chips and the ones that could not be fetched. */
class UndoRestoration(
    val row: PendingSendResponse,
    val attachments: List<ComposeAttachment>,
    val missing: List<String>,
)

/**
 * One composer, from the intent that opened it to the submit or discard that closes it. Subject,
 * body and the typed text of each recipient field are written here on every change, so a submit
 * reads exactly what is on screen; the fields read them back only when a revision says the store
 * replaced them.
 */
class ComposerStore(
    val intent: ComposeIntent,
    private val backend: ComposerBackend,
    private val scope: CoroutineScope,
    private val recovery: ComposeRecovery? = null,
    private val restoration: UndoRestoration? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val recoveryIntervalMs: Long = RECOVERY_INTERVAL_MS,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(ComposerState())
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    private var phase: ComposerPhase = ComposerPhase.Loading
    private var recipients = mutableMapOf<RecipientField, List<String>>()
    private var recipientText = mutableMapOf<RecipientField, String>()
    private var recipientNotes = mutableMapOf<RecipientField, String>()
    private var showsCcBcc = false
    private var fromOptions: List<FromAddress> = emptyList()
    private var selectedFromKey: String? = null
    private var subject = ""
    private var body = ""
    private var quote: ComposeQuote? = null
    private var quotedText = ""
    private var attachments: List<ComposeAttachment> = emptyList()
    private var unrestored: List<String> = emptyList()
    private var inReplyTo: String? = null
    private var references: List<String>? = null
    private var replacesMessageId: String? = null

    /** A body reopened from HTML, kept so an unchanged body goes out as the HTML it came in as. */
    private var loadedBody: Pair<String, String>? = null
    private var submitError: String? = null
    private var sendHint: String? = null
    private var recoverable: RecoveryContent? = null
    private var holdsUnsavedRestore = false
    private var submitting = false
    private var completed = false
    private var textRevision = 0
    private var recipientRevision = 0
    private var baseline = Snapshot()
    private var lastRecoveryWrite: Snapshot? = null

    // One per kind for the composer's lifetime: the server answers a repeat with the row the first created.
    private val sendKey = UUID.randomUUID().toString()
    private val draftKey = UUID.randomUUID().toString()

    private data class Snapshot(
        val recipients: Map<RecipientField, List<String>> = emptyMap(),
        val subject: String = "",
        val body: String = "",
        val attachmentIds: List<String> = emptyList(),
        val quote: ComposeQuote? = null,
    )

    val recoveryKey: String get() = synchronized(lock) { ComposeRecovery.key(replacesMessageId, inReplyTo) }

    /** Reads what the intent names. Safe to call again after a failure. */
    suspend fun load() {
        update { phase = ComposerPhase.Loading }
        try {
            val (accounts, identities) =
                coroutineScope {
                    val a = async { backend.accounts() }
                    val i = async { backend.identities() }
                    a.await() to i.await()
                }
            apply(accounts, identities)
            val offered = recovery?.read(ComposeRecovery.key(replacesMessageId, inReplyTo))
            update {
                showsCcBcc = recipients(RecipientField.Cc).isNotEmpty() || recipients(RecipientField.Bcc).isNotEmpty()
                baseline = snapshot()
                recoverable = offered
                textRevision++
                recipientRevision++
                phase = ComposerPhase.Editing
            }
        } catch (e: ApiError) {
            update { phase = ComposerPhase.LoadFailed(e.userMessage) }
        } catch (e: ComposerError) {
            update { phase = ComposerPhase.LoadFailed(e.message.orEmpty()) }
        }
    }

    private suspend fun apply(
        accounts: List<AccountResponse>,
        identities: List<IdentityResponse>,
    ) {
        val every = IdentityRules.pickable(accounts, identities)
        when (val kind = intent.kind) {
            is ComposeIntent.Kind.New ->
                update {
                    fromOptions = every
                    selectedFromKey =
                        IdentityRules.resolve(every, listOf(kind.accountId, accounts.firstOrNull()?.id))?.key
                }
            is ComposeIntent.Kind.Mailto -> {
                val link = kind.link
                update {
                    fromOptions = every
                    selectedFromKey = IdentityRules.resolve(every, emptyList())?.key
                    recipients[RecipientField.To] = link.to
                    recipients[RecipientField.Cc] = link.cc
                    recipients[RecipientField.Bcc] = link.bcc
                    subject = link.subject.orEmpty()
                    body = link.body.orEmpty()
                }
            }
            is ComposeIntent.Kind.Reply -> loadReply(kind.messageId, ReplyMode.Reply, accounts, identities, every)
            is ComposeIntent.Kind.ReplyAll -> loadReply(kind.messageId, ReplyMode.ReplyAll, accounts, identities, every)
            is ComposeIntent.Kind.Forward -> loadForward(kind.messageId, identities, every)
            is ComposeIntent.Kind.Draft -> loadDraft(kind.messageId, identities, every)
            is ComposeIntent.Kind.UndoRestore -> applyRestoration(identities, every)
        }
    }

    private suspend fun loadReply(
        messageId: String,
        mode: ReplyMode,
        accounts: List<AccountResponse>,
        identities: List<IdentityResponse>,
        every: List<FromAddress>,
    ) {
        val (message, quoteHtml) = messageAndQuote(messageId)
        val own = identities.filter { it.accountId == message.accountId }
        val ownAddresses = accounts.filter { it.id == message.accountId }.map { it.imapUser } + own.map { it.address }
        val draft = ComposeReply.reply(message, ownAddresses, mode, zone)
        update {
            recipients[RecipientField.To] = draft.to
            recipients[RecipientField.Cc] = draft.cc
            subject = draft.subject
            quote = ComposeQuote(quoteHtml, draft.attribution)
            quotedText = draft.quotedText
            inReplyTo = draft.inReplyTo
            references = draft.references
            scopeFrom(message.accountId, every, IdentityRules.matchIdentity(addressed(message), own))
        }
    }

    /** The original's attachments come along; one that cannot be downloaded is named rather than silently missing. */
    private suspend fun loadForward(
        messageId: String,
        identities: List<IdentityResponse>,
        every: List<FromAddress>,
    ) {
        val (message, quoteHtml) = messageAndQuote(messageId)
        val forward = ComposeReply.forward(message, zone)
        val (carried, missing) = download(message.id, message.attachments)
        val own = identities.filter { it.accountId == message.accountId }
        update {
            subject = forward.subject
            quote = ComposeQuote(quoteHtml, forward.attribution)
            quotedText = forward.quotedText
            attachments = carried
            unrestored = missing
            scopeFrom(message.accountId, every, IdentityRules.matchIdentity(addressed(message), own))
        }
    }

    /**
     * A draft's body is read from the quote endpoint rather than `body_html`, whose copy is shaped for
     * display. Its quote is split back out into the card, and its files come along so saving it again
     * keeps them.
     */
    private suspend fun loadDraft(
        messageId: String,
        identities: List<IdentityResponse>,
        every: List<FromAddress>,
    ) {
        val (message, quoteHtml) = messageAndQuote(messageId)
        val (authored, splitQuote) = ComposeHtml.split(quoteHtml)
        val (carried, missing) = download(message.id, message.attachments)
        val own = identities.filter { it.accountId == message.accountId }
        update {
            loadBody(authored)
            quote = splitQuote
            quotedText = splitQuote?.let { ComposeReply.draftQuotedText(message.bodyText, it.attribution) }.orEmpty()
            recipients[RecipientField.To] = message.toAddrs?.addresses.orEmpty()
            recipients[RecipientField.Cc] = message.ccAddrs?.addresses.orEmpty()
            recipients[RecipientField.Bcc] = message.bccAddrs?.addresses.orEmpty()
            subject = message.subject.orEmpty()
            inReplyTo = message.inReplyTo
            references = message.references
            replacesMessageId = message.id
            attachments = carried
            unrestored = missing
            scopeFrom(message.accountId, every, IdentityRules.matchIdentity(listOf(message.fromAddr), own))
        }
    }

    /** Everything the cancelled send staged comes back; it counts as unsaved from the start, since the staged row is gone. */
    private fun applyRestoration(
        identities: List<IdentityResponse>,
        every: List<FromAddress>,
    ) {
        val restored = restoration ?: throw ComposerError("The cancelled message could not be found.")
        val row = restored.row
        val (authored, splitQuote) = ComposeHtml.split(row.bodyHtml.orEmpty())
        val own = identities.filter { it.accountId == row.accountId }
        update {
            recipients[RecipientField.To] = row.to
            recipients[RecipientField.Cc] = row.cc.orEmpty()
            recipients[RecipientField.Bcc] = row.bcc.orEmpty()
            subject = row.subject.orEmpty()
            loadBody(authored)
            quote = splitQuote
            quotedText = splitQuote?.let(ComposeReply::quotedPlainText).orEmpty()
            attachments = restored.attachments
            unrestored = restored.missing
            inReplyTo = row.inReplyTo
            references = row.references
            replacesMessageId = row.replacesMessageId
            scopeFrom(row.accountId, every, IdentityRules.matchIdentity(listOf(row.fromAddr), own))
            holdsUnsavedRestore = true
        }
    }

    private suspend fun messageAndQuote(messageId: String) =
        coroutineScope {
            val message = async { backend.message(messageId) }
            val quote = async { backend.quote(messageId) }
            message.await() to quote.await()
        }

    private suspend fun download(
        messageId: String,
        summaries: List<com.frederikberg.mailverdict.contract.AttachmentSummary>,
    ): Pair<List<ComposeAttachment>, List<String>> {
        val carried = mutableListOf<ComposeAttachment>()
        val missing = mutableListOf<String>()
        for (summary in summaries) {
            val name = summary.filename ?: "attachment"
            try {
                val raw = backend.attachment(messageId, summary.id)
                carried += ComposeAttachment(name, summary.contentType ?: raw.contentType, raw.data)
            } catch (_: ApiError) {
                missing += name
            }
        }
        return carried to missing
    }

    private fun addressed(message: com.frederikberg.mailverdict.contract.MessageDetail): List<String?> =
        message.toAddrs?.addresses.orEmpty() + message.ccAddrs?.addresses.orEmpty()

    /** A reply, forward, draft or restore sends from its own account, as whichever identity the message was addressed to. */
    private fun scopeFrom(
        accountId: String,
        every: List<FromAddress>,
        matched: String?,
    ) {
        fromOptions = every.filter { it.accountId == accountId }
        selectedFromKey = matched ?: IdentityRules.defaultAddress(fromOptions, accountId)?.key
    }

    private fun loadBody(html: String) {
        val text = ComposeHtml.toText(html)
        body = text
        loadedBody = if (html.isBlank()) null else text to html
    }

    // Editing

    fun setSubject(value: String) = update { subject = value }

    fun setBody(value: String) = update { body = value }

    fun showCcBcc() = update { showsCcBcc = true }

    fun selectFrom(key: String) = update { if (fromOptions.any { it.key == key }) selectedFromKey = key }

    fun recipientText(field: RecipientField): String = synchronized(lock) { recipientText[field].orEmpty() }

    /** A comma or semicolon, typed or pasted, commits what came before it. */
    fun setRecipientText(
        field: RecipientField,
        text: String,
    ) {
        update { recipientText[field] = text }
        if (',' in text || ';' in text) commitRecipientText(field)
    }

    /** Turns the field's text into recipients; whatever is not an address stays in the field with a note. */
    fun commitRecipientText(field: RecipientField) =
        update {
            val text = recipientText[field].orEmpty()
            if (text.isBlank()) {
                if (text.isNotEmpty()) recipientRevision++
                recipientText[field] = ""
                recipientNotes.remove(field)
            } else {
                val result = ComposeRecipients.commit(text, recipients(field))
                recipients[field] = result.recipients
                recipientText[field] = result.invalid.joinToString(", ")
                ComposeRecipients.invalidNote(result.invalid)?.let { recipientNotes[field] = it }
                    ?: recipientNotes.remove(field)
                if (field == RecipientField.To && result.recipients.isNotEmpty()) sendHint = null
                recipientRevision++
            }
        }

    /** A suggestion picked replaces whatever was typed to find it. */
    fun addRecipient(
        field: RecipientField,
        address: String,
    ) = update {
        val existing = recipients(field)
        if (existing.none { it.equals(address, ignoreCase = true) }) recipients[field] = existing + address
        recipientText[field] = ""
        recipientNotes.remove(field)
        if (field == RecipientField.To) sendHint = null
        recipientRevision++
    }

    fun removeRecipient(
        field: RecipientField,
        address: String,
    ) = update { recipients[field] = recipients(field).filter { it != address } }

    fun addAttachments(added: List<ComposeAttachment>) = update { attachments = attachments + added }

    fun removeAttachment(id: String) = update { attachments = attachments.filter { it.id != id } }

    fun removeQuote() =
        update {
            quote = null
            quotedText = ""
        }

    // Submitting

    /**
     * Send or Save Draft. The guard is checked and set before the first suspension, so a second tap
     * reaching this before the first request returns does nothing.
     */
    suspend fun submit(draft: Boolean): SubmitOutcome {
        val input =
            synchronized(lock) {
                if (submitting || completed || phase != ComposerPhase.Editing) return SubmitOutcome.Ignored
                RecipientField.entries.forEach(::commitLocked)
                if (!draft) {
                    if (recipientNotes.isNotEmpty()) return blocked()
                    if (recipients(RecipientField.To).isEmpty()) {
                        sendHint = "Add at least one recipient"
                        return blocked()
                    }
                }
                val from =
                    selectedFrom() ?: run {
                        submitError = "Add an account before writing a message."
                        return blocked()
                    }
                submitting = true
                submitError = null
                if (!draft) phase = ComposerPhase.Sending
                SubmissionInput(
                    accountId = from.accountId,
                    kind = if (draft) "draft" else "send",
                    to = recipients(RecipientField.To),
                    cc = recipients(RecipientField.Cc),
                    bcc = recipients(RecipientField.Bcc),
                    subject = subject,
                    body = body,
                    authoredHtml = loadedBody?.takeIf { it.first == body }?.second,
                    quote = quote,
                    quotedText = quotedText,
                    inReplyTo = inReplyTo,
                    references = references,
                    identityId = from.identityId,
                    replacesMessageId = replacesMessageId,
                    attachments = attachments,
                    idempotencyKey = if (draft) draftKey else sendKey,
                )
            }
        publish()
        val (request, uploads) = ComposeSubmission.build(input)
        return try {
            val result = backend.submit(request, uploads)
            val key = recoveryKey
            update { completed = true }
            recovery?.clear(key)
            when (result) {
                is OutboxCreateResult.Pending -> SubmitOutcome.Pending(result.send)
                is OutboxCreateResult.Sent -> if (draft) SubmitOutcome.DraftSaved else SubmitOutcome.Sent
            }
        } catch (e: ApiError) {
            val message = "${if (draft) "Not saved" else "Not sent"}: ${e.userMessage}"
            update {
                submitting = false
                phase = ComposerPhase.Editing
                submitError = message
            }
            SubmitOutcome.Failed(message)
        }
    }

    /** Leaving keeps what was written: unsaved work is saved as a draft, an untouched composer just closes. */
    suspend fun leave(): LeaveOutcome {
        val dirty = synchronized(lock) { dirtyLocked() }
        if (!dirty || phase is ComposerPhase.LoadFailed) {
            discard()
            return LeaveOutcome.Close(null)
        }
        return when (val outcome = submit(draft = true)) {
            SubmitOutcome.DraftSaved, SubmitOutcome.Sent, is SubmitOutcome.Pending -> LeaveOutcome.Close(DRAFT_SAVED)
            is SubmitOutcome.Failed -> LeaveOutcome.Stay(outcome.message)
            SubmitOutcome.Blocked -> LeaveOutcome.Stay(_state.value.submitError ?: "Not saved")
            SubmitOutcome.Ignored -> LeaveOutcome.Close(null)
        }
    }

    /** A deliberate discard: the one way out that sends nothing and keeps nothing. */
    fun discard() {
        val key = recoveryKey
        update { completed = true }
        recovery?.clear(key)
    }

    // Recovery

    private var recoveryJob: Job? = null

    /** Writes the snapshot every [recoveryIntervalMs] while there is something unsaved, until [stop]. */
    fun startRecovery() {
        if (recovery == null || recoveryJob != null) return
        recoveryJob =
            scope.launch {
                while (isActive) {
                    delay(recoveryIntervalMs)
                    saveRecoverySnapshot()
                }
            }
    }

    fun stop() {
        recoveryJob?.cancel()
    }

    /** Also called when the app goes to the background. Writes only what changed since the last write. */
    fun saveRecoverySnapshot() {
        val recovery = recovery ?: return
        val (key, content) =
            synchronized(lock) {
                if (!dirtyLocked()) return
                val now = snapshot()
                if (now == lastRecoveryWrite) return
                lastRecoveryWrite = now
                ComposeRecovery.key(replacesMessageId, inReplyTo) to
                    RecoveryContent(
                        recipients(RecipientField.To),
                        recipients(RecipientField.Cc),
                        recipients(RecipientField.Bcc),
                        subject,
                        body,
                        quote,
                        attachments,
                    )
            }
        recovery.write(key, content)
    }

    fun restoreRecovered() =
        update {
            val content = recoverable ?: return@update
            recipients[RecipientField.To] = content.to
            recipients[RecipientField.Cc] = content.cc
            recipients[RecipientField.Bcc] = content.bcc
            subject = content.subject
            body = content.body
            if (content.quote != quote) {
                quote = content.quote
                quotedText = content.quote?.let(ComposeReply::quotedPlainText).orEmpty()
            }
            attachments = content.attachments
            showsCcBcc = showsCcBcc || content.cc.isNotEmpty() || content.bcc.isNotEmpty()
            holdsUnsavedRestore = true
            recoverable = null
            textRevision++
            recipientRevision++
        }

    fun dismissRecovered() = update { recoverable = null }

    // State

    private fun recipients(field: RecipientField) = recipients[field].orEmpty()

    private fun selectedFrom(): FromAddress? =
        fromOptions.firstOrNull { it.key == selectedFromKey } ?: fromOptions.firstOrNull()

    private fun snapshot() =
        Snapshot(recipients.filterValues { it.isNotEmpty() }, subject, body, attachments.map { it.id }, quote)

    private fun dirtyLocked(): Boolean {
        if (completed || (phase != ComposerPhase.Editing && phase != ComposerPhase.Sending)) return false
        return holdsUnsavedRestore || snapshot() != baseline || recipientText.values.any { it.isNotBlank() }
    }

    private fun commitLocked(field: RecipientField) {
        val text = recipientText[field].orEmpty()
        if (text.isBlank()) return
        val result = ComposeRecipients.commit(text, recipients(field))
        recipients[field] = result.recipients
        recipientText[field] = result.invalid.joinToString(", ")
        ComposeRecipients.invalidNote(result.invalid)?.let { recipientNotes[field] = it }
            ?: recipientNotes.remove(field)
        recipientRevision++
    }

    private fun blocked(): SubmitOutcome {
        publishLocked()
        return SubmitOutcome.Blocked
    }

    private inline fun update(block: () -> Unit) {
        synchronized(lock) {
            block()
            publishLocked()
        }
    }

    private fun publish() = synchronized(lock) { publishLocked() }

    private fun publishLocked() {
        val trimmed = subject.trim()
        _state.value =
            ComposerState(
                phase = phase,
                title =
                    when {
                        intent.kind is ComposeIntent.Kind.Draft -> DRAFT_TITLE
                        trimmed.isEmpty() -> NEW_TITLE
                        else -> trimmed
                    },
                recipients = recipients.toMap(),
                recipientNotes = recipientNotes.toMap(),
                showsCcBcc = showsCcBcc,
                fromOptions = fromOptions,
                selectedFrom = selectedFrom(),
                subject = subject,
                body = body,
                quote = quote,
                quotePreview =
                    if (quote ==
                        _state.value.quote
                    ) {
                        _state.value.quotePreview
                    } else {
                        quote?.html?.let(ComposeHtml::toText).orEmpty()
                    },
                attachments = attachments,
                unrestoredAttachments = unrestored,
                submitError = submitError,
                sendHint = sendHint,
                recoverable = recoverable != null,
                isDirty = dirtyLocked(),
                textRevision = textRevision,
                recipientRevision = recipientRevision,
            )
    }

    companion object {
        const val NEW_TITLE = "New message"
        const val DRAFT_TITLE = "Draft"
        const val DRAFT_SAVED = "Draft saved"
        const val RECOVERY_INTERVAL_MS = 1_000L
    }
}

class ComposerError(
    message: String,
) : Exception(message)
