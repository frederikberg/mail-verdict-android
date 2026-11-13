package com.frederikberg.mailverdict.kit.live

import com.frederikberg.mailverdict.contract.SseEventName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * What one event means for the app's stores, never what a store should do about it: the effect (a
 * bounded window refresh, a badge recompute, a toast) is each store's own job.
 */
sealed interface LiveInvalidation {
    /** Every store refetches; a list store does a bounded window refresh rather than starting over, to never lose its place. */
    data object Resync : LiveInvalidation

    data class MailNew(
        val accountId: String?,
        val folderId: String?,
        val messageId: String?,
    ) : LiveInvalidation

    /**
     * [changed] names the columns that moved. For a move, [oldFolderId] is the folder the message left,
     * so only lists showing either folder need to refetch.
     */
    data class MailUpdated(
        val accountId: String?,
        val folderId: String?,
        val oldFolderId: String?,
        val messageId: String?,
        val changed: List<String>,
    ) : LiveInvalidation

    data class MailDeleted(
        val accountId: String?,
        val folderId: String?,
        val messageId: String?,
    ) : LiveInvalidation

    data class VerdictIssued(
        val accountId: String?,
        val messageId: String?,
        val isSpam: Boolean?,
    ) : LiveInvalidation

    data class FolderSynced(
        val folderId: String?,
    ) : LiveInvalidation

    data object FoldersChanged : LiveInvalidation

    data object AlertsChanged : LiveInvalidation

    data object NotificationsChanged : LiveInvalidation

    data object AccountsChanged : LiveInvalidation

    data class OutboxUpdated(
        val payload: OutboxEventPayload,
    ) : LiveInvalidation

    data class SettingsChanged(
        val category: String?,
    ) : LiveInvalidation

    data class IdentitiesChanged(
        val accountId: String?,
    ) : LiveInvalidation

    /** `change` is `created`, `updated` or `deleted`. */
    data class OrderChanged(
        val orderId: String?,
        val change: String?,
    ) : LiveInvalidation

    /** A calendar reply's outbox row, or `calendar.object`: only the invitation card and event detail refresh, never a mail toast or list. */
    data object InvitationOrEventChanged : LiveInvalidation

    /** Every event name this app has no effect for. Carrying the name keeps the decision visible to a test rather than a silent drop. */
    data class Ignored(
        val name: SseEventName,
    ) : LiveInvalidation

    /** A name the vendored contract does not list: the contract has drifted, which is not the same as the app choosing to ignore it. */
    data class Unknown(
        val name: String,
    ) : LiveInvalidation

    companion object {
        /** Columns of a message whose change moves no folder count: flags other than read, labels, and the server's own bookkeeping. */
        private val columnsWithoutCountEffect = setOf("is_flagged", "is_answered", "keywords", "imap_uid")

        /** Whether a `mail.updated` carrying these changed columns can have moved a folder's unread or total count. An empty list names nothing and is assumed to. */
        fun messageUpdateMovesCounts(changed: List<String>): Boolean =
            changed.isEmpty() || !columnsWithoutCountEffect.containsAll(changed)

        fun from(record: SseRecord): LiveInvalidation {
            val name = SseEventName(record.name)
            if (!name.isKnown) return Unknown(record.name)
            val body = SseBody.parse(record.data)
            return when (name) {
                SseEventName.Connected -> Ignored(name)
                SseEventName.Resync -> Resync
                SseEventName.MailNew -> MailNew(body.string("account_id"), body.string("folder_id"), body.string("id"))
                SseEventName.MailUpdated ->
                    MailUpdated(
                        body.string("account_id"),
                        body.string("folder_id"),
                        body.string("old_folder_id"),
                        body.string("id"),
                        body.strings("changed"),
                    )
                SseEventName.MailDeleted ->
                    MailDeleted(
                        body.string("account_id"),
                        body.string("folder_id"),
                        body.string("id"),
                    )
                SseEventName.VerdictIssued ->
                    VerdictIssued(
                        body.string("account_id"),
                        body.string("message_id"),
                        body.bool("is_spam"),
                    )
                SseEventName.AlertNew, SseEventName.AlertDismissed -> AlertsChanged
                SseEventName.NotificationNew -> NotificationsChanged
                SseEventName.AccountChanged -> AccountsChanged
                // The payload carries only folder_id and backfill: the account only scopes the server's ring.
                SseEventName.FolderSynced -> FolderSynced(body.string("folder_id"))
                SseEventName.FolderChanged -> FoldersChanged
                SseEventName.OutboxUpdated -> {
                    val payload =
                        OutboxEventPayload(
                            id = body.string("id"),
                            changed = body.strings("changed"),
                            status = body.string("status"),
                            kind = body.string("kind"),
                            itip = body.string("itip"),
                        )
                    if (payload.itip == "reply") InvitationOrEventChanged else OutboxUpdated(payload)
                }
                SseEventName.SettingsChanged -> SettingsChanged(body.string("category"))
                SseEventName.IdentityChanged -> IdentitiesChanged(body.string("account_id"))
                SseEventName.OrderUpdated -> OrderChanged(body.string("order_id"), body.string("change"))
                SseEventName.CalendarObject -> InvitationOrEventChanged
                SseEventName.CalendarAccount,
                SseEventName.CalendarCollection,
                SseEventName.CalendarLinksChanged,
                SseEventName.ContactCollection,
                SseEventName.ContactObject,
                SseEventName.PipelineDocumentChanged,
                SseEventName.PipelineNotify,
                SseEventName.PipelineRunFinished,
                -> Ignored(name)
                else -> Unknown(record.name)
            }
        }
    }
}

/** `outbox.updated`'s own payload; SSE payload shapes are untyped dictionaries server side and not in the published contract. */
data class OutboxEventPayload(
    val id: String?,
    val changed: List<String> = emptyList(),
    val status: String? = null,
    val kind: String? = null,
    /** `reply` when the row is a calendar RSVP rather than a mail send. */
    val itip: String? = null,
)

/** A forgiving reader over one record's JSON body: it never throws, since a field missing or malformed inside a recognised event is no reason to drop the whole invalidation. */
internal class SseBody private constructor(
    private val fields: JsonObject,
) {
    fun string(key: String): String? = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    fun bool(key: String): Boolean? = (fields[key] as? JsonPrimitive)?.booleanOrNull

    fun strings(key: String): List<String> =
        (fields[key] as? JsonArray)
            ?.mapNotNull {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            }.orEmpty()

    companion object {
        fun parse(raw: String): SseBody =
            SseBody(
                runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()),
            )
    }
}
