package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.kit.api.lookupAlerts
import com.frederikberg.mailverdict.kit.net.ApiClient

/**
 * Which of the alerts a phone is still showing the server no longer wants shown: it has forgotten them
 * or they were dismissed, usually because the mail was read somewhere else.
 */
suspend fun staleAlertIds(
    api: ApiClient,
    shown: List<String>,
): List<String> {
    // A notification of another kind (tagged with a link, say) is not an alert id and is left alone.
    val ids = shown.filter { ALERT_ID.matches(it) }
    val live =
        ids
            .chunked(MAX_IDS_PER_LOOKUP)
            .flatMap { api.lookupAlerts(it) }
            .filter { it.dismissedAt == null }
            .map { it.id }
            .toSet()
    return ids.filter { it !in live }
}

private val ALERT_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** The server's limit on one lookup. */
private const val MAX_IDS_PER_LOOKUP = 200
