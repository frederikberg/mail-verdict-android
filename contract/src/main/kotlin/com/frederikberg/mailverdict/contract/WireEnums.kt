package com.frederikberg.mailverdict.contract

import kotlinx.serialization.Serializable

/*
 * Every enumeration the server sends is a string-backed value, never a Kotlin `enum`: a case the
 * server adds later must not fail the decode of the whole payload. Each class names the values this
 * app knows in `known` (ContractTest holds that set equal to the schema's), and a value outside it
 * is `isKnown == false` with the raw text preserved.
 */

@Serializable
@JvmInline
value class UserRole(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Admin = UserRole("admin")
        val User = UserRole("user")
        val known: Set<String> = setOf(Admin.raw, User.raw)
    }
}

@Serializable
@JvmInline
value class FolderKind(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Imap = FolderKind("imap")
        val Glacier = FolderKind("glacier")
        val known: Set<String> = setOf(Imap.raw, Glacier.raw)
    }
}

/** What `POST /messages/{id}/action` can do to one message. */
@Serializable
@JvmInline
value class MessageActionKind(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val MarkRead = MessageActionKind("mark_read")
        val MarkUnread = MessageActionKind("mark_unread")
        val Flag = MessageActionKind("flag")
        val Unflag = MessageActionKind("unflag")
        val Move = MessageActionKind("move")
        val Archive = MessageActionKind("archive")
        val Trash = MessageActionKind("trash")
        val Expunge = MessageActionKind("expunge")
        val Spam = MessageActionKind("spam")
        val NotSpam = MessageActionKind("not_spam")
        val KeywordAdd = MessageActionKind("keyword_add")
        val KeywordRemove = MessageActionKind("keyword_remove")
        val known: Set<String> =
            setOf(
                MarkRead,
                MarkUnread,
                Flag,
                Unflag,
                Move,
                Archive,
                Trash,
                Expunge,
                Spam,
                NotSpam,
                KeywordAdd,
                KeywordRemove,
            ).map { it.raw }.toSet()
    }
}

/** What `POST /accounts/{id}/messages/bulk-action` can do; a subset of [MessageActionKind]. */
@Serializable
@JvmInline
value class BulkActionKind(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Move = BulkActionKind("move")
        val MarkRead = BulkActionKind("mark_read")
        val MarkUnread = BulkActionKind("mark_unread")
        val Flag = BulkActionKind("flag")
        val Unflag = BulkActionKind("unflag")
        val Archive = BulkActionKind("archive")
        val Trash = BulkActionKind("trash")
        val Expunge = BulkActionKind("expunge")
        val Spam = BulkActionKind("spam")
        val NotSpam = BulkActionKind("not_spam")
        val known: Set<String> =
            setOf(Move, MarkRead, MarkUnread, Flag, Unflag, Archive, Trash, Expunge, Spam, NotSpam)
                .map { it.raw }
                .toSet()
    }
}

/** Which messages of a folder a bulk action's scope covers. */
@Serializable
@JvmInline
value class BulkScopeFilter(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Unread = BulkScopeFilter("unread")
        val All = BulkScopeFilter("all")
        val known: Set<String> = setOf(Unread.raw, All.raw)
    }
}

@Serializable
@JvmInline
value class PushTransport(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val WebPush = PushTransport("webpush")
        val Apns = PushTransport("apns")
        val known: Set<String> = setOf(WebPush.raw, Apns.raw)
    }
}

/** A notification channel a push subscription can mute. */
@Serializable
@JvmInline
value class PushChannel(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Mail = PushChannel("mail")
        val System = PushChannel("system")
        val known: Set<String> = setOf(Mail.raw, System.raw)
    }
}
