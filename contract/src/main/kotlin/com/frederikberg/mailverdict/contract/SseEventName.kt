package com.frederikberg.mailverdict.contract

import kotlinx.serialization.Serializable

/**
 * An event name of `GET /events`. Names the server adds later are preserved as raw text and answered
 * by the live-event table with an "unknown" invalidation; ContractTest keeps [known] equal to the
 * published `sse-events.json`.
 */
@Serializable
@JvmInline
value class SseEventName(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Connected = SseEventName("connected")
        val Resync = SseEventName("resync")
        val MailNew = SseEventName("mail.new")
        val MailUpdated = SseEventName("mail.updated")
        val MailDeleted = SseEventName("mail.deleted")
        val VerdictIssued = SseEventName("verdict.issued")
        val AlertNew = SseEventName("alert.new")
        val AlertDismissed = SseEventName("alert.dismissed")
        val NotificationNew = SseEventName("notification.new")
        val AccountChanged = SseEventName("account.changed")
        val FolderSynced = SseEventName("folder.synced")
        val FolderChanged = SseEventName("folder.changed")
        val OutboxUpdated = SseEventName("outbox.updated")
        val SettingsChanged = SseEventName("settings.changed")
        val IdentityChanged = SseEventName("identity.changed")
        val CalendarAccount = SseEventName("calendar.account")
        val CalendarCollection = SseEventName("calendar.collection")
        val CalendarLinksChanged = SseEventName("calendar.links_changed")
        val CalendarObject = SseEventName("calendar.object")
        val ContactCollection = SseEventName("contact.collection")
        val ContactObject = SseEventName("contact.object")
        val PipelineDocumentChanged = SseEventName("pipeline.document_changed")
        val PipelineNotify = SseEventName("pipeline.notify")
        val PipelineRunFinished = SseEventName("pipeline.run_finished")
        val OrderUpdated = SseEventName("order.updated")

        /** Every name the server publishes, in the order of the list above. */
        val all: List<SseEventName> =
            listOf(
                Connected,
                Resync,
                MailNew,
                MailUpdated,
                MailDeleted,
                VerdictIssued,
                AlertNew,
                AlertDismissed,
                NotificationNew,
                AccountChanged,
                FolderSynced,
                FolderChanged,
                OutboxUpdated,
                SettingsChanged,
                IdentityChanged,
                CalendarAccount,
                CalendarCollection,
                CalendarLinksChanged,
                CalendarObject,
                ContactCollection,
                ContactObject,
                PipelineDocumentChanged,
                PipelineNotify,
                PipelineRunFinished,
                OrderUpdated,
            )
        val known: Set<String> = all.map { it.raw }.toSet()
    }
}
