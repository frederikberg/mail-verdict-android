package com.frederikberg.mailverdict.compose

import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.kit.compose.ComposeArea
import com.frederikberg.mailverdict.kit.compose.ComposeIntent
import com.frederikberg.mailverdict.kit.compose.FileComposeRecovery
import com.frederikberg.mailverdict.kit.session.Connection
import com.frederikberg.mailverdict.maillist.serverDirectory
import com.frederikberg.mailverdict.nav.Route
import java.io.File

/** The composer stores of [connection]; unsent work is kept in files per server, none at all in a fixture run. */
fun AppGraph.composeArea(connection: Connection): ComposeArea =
    connectionStores.get(connection, ComposeArea::class) { scope ->
        ComposeArea(
            connection,
            scope,
            if (fixtureMode) null else FileComposeRecovery(File(composeRoot, serverDirectory(connection.serverUrl))),
        )
    }

/** The navigation route that opens [this] composer. */
fun ComposeIntent.toRoute(): Route.Compose =
    when (val kind = kind) {
        is ComposeIntent.Kind.New -> Route.Compose(id, KIND_NEW, accountId = kind.accountId)
        is ComposeIntent.Kind.Reply -> Route.Compose(id, KIND_REPLY, messageId = kind.messageId)
        is ComposeIntent.Kind.ReplyAll -> Route.Compose(id, KIND_REPLY_ALL, messageId = kind.messageId)
        is ComposeIntent.Kind.Forward -> Route.Compose(id, KIND_FORWARD, messageId = kind.messageId)
        is ComposeIntent.Kind.Draft -> Route.Compose(id, KIND_DRAFT, messageId = kind.messageId)
        is ComposeIntent.Kind.UndoRestore -> Route.Compose(id, KIND_UNDO, pendingSendId = kind.pendingSendId)
        is ComposeIntent.Kind.Mailto -> Route.Compose(id, KIND_MAILTO, mailto = kind.url)
    }

fun Route.Compose.toIntent(): ComposeIntent =
    ComposeIntent(
        when (kind) {
            KIND_REPLY -> ComposeIntent.Kind.Reply(messageId!!)
            KIND_REPLY_ALL -> ComposeIntent.Kind.ReplyAll(messageId!!)
            KIND_FORWARD -> ComposeIntent.Kind.Forward(messageId!!)
            KIND_DRAFT -> ComposeIntent.Kind.Draft(messageId!!)
            KIND_UNDO -> ComposeIntent.Kind.UndoRestore(pendingSendId!!)
            KIND_MAILTO -> ComposeIntent.Kind.Mailto(mailto.orEmpty())
            else -> ComposeIntent.Kind.New(accountId)
        },
        id,
    )

private const val KIND_NEW = "new"
private const val KIND_REPLY = "reply"
private const val KIND_REPLY_ALL = "reply-all"
private const val KIND_FORWARD = "forward"
private const val KIND_DRAFT = "draft"
private const val KIND_UNDO = "undo"
private const val KIND_MAILTO = "mailto"
