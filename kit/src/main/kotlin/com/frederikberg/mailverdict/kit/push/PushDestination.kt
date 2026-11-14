package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.reader.MessagePlaceResolver
import com.frederikberg.mailverdict.kit.search.MessagePlace
import com.frederikberg.mailverdict.kit.search.place

/** The screen a notification tap ends up on. */
sealed interface PushDestination {
    data class Message(
        val place: MessagePlace,
    ) : PushDestination

    data object Alerts : PushDestination
}

/**
 * A notification names only the message; where it lives now (it may have moved since the push was
 * sent) comes from the server. A message that cannot be found any more, or a server that cannot be
 * reached, lands on the alert list, which says what is going on.
 */
suspend fun PushTarget.resolve(
    resolver: MessagePlaceResolver,
    api: ApiClient,
): PushDestination =
    when (this) {
        PushTarget.Alerts -> PushDestination.Alerts
        is PushTarget.Message ->
            resolver.place(api, messageId)?.let { PushDestination.Message(it) }
                ?: PushDestination.Alerts
    }
