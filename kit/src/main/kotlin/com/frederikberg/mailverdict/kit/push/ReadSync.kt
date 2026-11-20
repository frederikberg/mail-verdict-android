package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.kit.api.lookupAlerts
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

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

/** The system notifications this phone is showing for alerts, tagged by alert id. */
interface ShownNotifications {
    fun shownTags(): List<String>

    fun cancel(alertId: String)
}

/** Withdraws what [staleAlertIds] names from [shown]. Throws the lookup's [ApiError]. */
suspend fun withdrawStaleNotifications(
    api: ApiClient,
    shown: ShownNotifications,
) {
    staleAlertIds(api, shown.shownTags()).forEach(shown::cancel)
}

/**
 * Runs [withdraw] whenever the stream says alerts changed (a dismissal elsewhere is what matters; a
 * new alert costs one lookup that finds nothing stale) or resynchronised, which is also how a
 * reconnect after the app was away reaches here. A failed run is [onFailure]'s to note and the next
 * trigger tries again.
 */
suspend fun withdrawOnAlertChanges(
    events: Flow<List<LiveEvent>>,
    onFailure: (ApiError) -> Unit = {},
    withdraw: suspend () -> Unit,
) {
    events.collect { batch ->
        val changed =
            batch.any {
                it.invalidation is LiveInvalidation.AlertsChanged || it.invalidation is LiveInvalidation.Resync
            }
        if (!changed) return@collect
        try {
            withdraw()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            onFailure(e)
        }
    }
}
