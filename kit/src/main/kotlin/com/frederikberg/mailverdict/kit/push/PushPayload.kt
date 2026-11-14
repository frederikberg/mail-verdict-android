package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.contract.PushChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.net.URI

/** What the server sends with every Web Push: the notification's text, where tapping it leads, and the alert id as its tag. */
@Serializable
data class PushPayload(
    val title: String? = null,
    val body: String? = null,
    val url: String? = null,
    val tag: String? = null,
) {
    /** The message a mail alert's link names (`/?message=<id>`); null for any other alert. */
    val messageId: String? get() = messageIdOf(url)

    /** New mail is the mail channel; every other kind of alert is the system one, as in the server's own split. */
    val channel: PushChannel get() = if (messageId != null) PushChannel.Mail else PushChannel.System

    val target: PushTarget get() = messageId?.let { PushTarget.Message(it) } ?: PushTarget.Alerts

    companion object {
        /** Anything that is not the expected JSON still shows as a notification, as a bare title. */
        fun parse(content: ByteArray): PushPayload =
            try {
                ContractJson.decodeFromString(serializer(), content.decodeToString())
            } catch (_: SerializationException) {
                PushPayload()
            } catch (_: IllegalArgumentException) {
                PushPayload()
            }

        fun messageIdOf(url: String?): String? {
            val query =
                try {
                    URI(url ?: return null).rawQuery
                } catch (_: java.net.URISyntaxException) {
                    return null
                } ?: return null
            return query
                .split('&')
                .map { it.substringBefore('=') to it.substringAfter('=', "") }
                .firstOrNull { it.first == "message" }
                ?.second
                ?.takeIf { it.isNotEmpty() }
        }
    }
}

/** Where tapping a notification leads. */
sealed interface PushTarget {
    data class Message(
        val messageId: String,
    ) : PushTarget

    /** An alert that names no message: the alerts list shows it with its context. */
    data object Alerts : PushTarget
}
