package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.kit.actions.UiAction
import java.net.URLDecoder
import java.util.UUID

/** What opened a composer. [id] tells two composers of the same kind apart. */
data class ComposeIntent(
    val kind: Kind,
    val id: String = UUID.randomUUID().toString(),
) {
    sealed interface Kind {
        /** A fresh message; [accountId] is the account the person was reading, if any. */
        data class New(
            val accountId: String? = null,
        ) : Kind

        data class Reply(
            val messageId: String,
        ) : Kind

        data class ReplyAll(
            val messageId: String,
        ) : Kind

        data class Forward(
            val messageId: String,
        ) : Kind

        /** A saved draft reopened for editing. */
        data class Draft(
            val messageId: String,
        ) : Kind

        /** A `mailto:` link tapped in a message, kept as written so it survives as a navigation argument. */
        data class Mailto(
            val url: String,
        ) : Kind {
            val link: MailtoLink get() = MailtoLink.parse(url) ?: MailtoLink()
        }

        /** A send taken back inside its undo window, reopened with everything it staged. */
        data class UndoRestore(
            val pendingSendId: String,
        ) : Kind
    }

    companion object {
        /** The composer one of the answering actions opens on [messageId]; null for any other action. */
        fun answering(
            action: UiAction,
            messageId: String,
        ): ComposeIntent? =
            when (action) {
                UiAction.Reply -> Kind.Reply(messageId)
                UiAction.ReplyAll -> Kind.ReplyAll(messageId)
                UiAction.Forward -> Kind.Forward(messageId)
                UiAction.EditDraft -> Kind.Draft(messageId)
                else -> null
            }?.let(::ComposeIntent)
    }

    /** A message with nobody to address yet starts in the To field; everything else starts in the body. */
    val focusesRecipients: Boolean
        get() =
            when (kind) {
                is Kind.New -> true
                is Kind.Mailto -> kind.link.to.isEmpty()
                else -> false
            }
}

/** A parsed `mailto:` link: the web's `lib/mailto.ts`. */
data class MailtoLink(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String? = null,
    val body: String? = null,
) {
    companion object {
        /**
         * Null for anything that is not a `mailto:` link or names nothing to fill in: a composer opening
         * with the recipient silently missing is worse than one opening blank. A value that does not
         * decode passes through as it is.
         */
        fun parse(raw: String): MailtoLink? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("mailto:", ignoreCase = true)) return null
            val rest = trimmed.substring("mailto:".length)
            val path = rest.substringBefore('?')
            val query =
                rest
                    .substringAfter('?', "")
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .associate { pair ->
                        decode(pair.substringBefore('=')).lowercase() to decode(pair.substringAfter('=', ""))
                    }
            // A parameter may also repeat the path's recipients; the union is what the sender meant.
            val to = (addresses(path, decode = true) + addresses(query["to"])).distinct()
            val link =
                MailtoLink(
                    to = to,
                    cc = addresses(query["cc"]),
                    bcc = addresses(query["bcc"]),
                    subject = query["subject"]?.ifEmpty { null },
                    body = query["body"]?.ifEmpty { null },
                )
            val empty =
                link.to.isEmpty() &&
                    link.cc.isEmpty() &&
                    link.bcc.isEmpty() &&
                    link.subject == null &&
                    link.body == null
            return if (empty) null else link
        }

        private fun addresses(
            value: String?,
            decode: Boolean = false,
        ): List<String> =
            value
                .orEmpty()
                .split(',')
                .map { (if (decode) decode(it) else it).trim() }
                .filter { it.isNotEmpty() }

        // `+` is a literal in a mailto link, never a space.
        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
    }
}
