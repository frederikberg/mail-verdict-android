package com.frederikberg.mailverdict.kit.alerts

import com.frederikberg.mailverdict.contract.AlertResponse
import com.frederikberg.mailverdict.contract.NotificationResponse
import com.frederikberg.mailverdict.kit.api.acknowledgeAllNotifications
import com.frederikberg.mailverdict.kit.api.acknowledgeNotification
import com.frederikberg.mailverdict.kit.api.dismissAlert
import com.frederikberg.mailverdict.kit.api.dismissAllAlerts
import com.frederikberg.mailverdict.kit.api.getAlertBadge
import com.frederikberg.mailverdict.kit.api.listAccounts
import com.frederikberg.mailverdict.kit.api.listAlerts
import com.frederikberg.mailverdict.kit.api.listNotifications
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** Which of the two lists an alert belongs to: new mail, or everything else the server raises. */
object AlertKinds {
    const val MAIL = "mail"

    fun isMail(kind: String) = kind == MAIL
}

/** The message subject a notification carries in its detail, when it has one. */
val NotificationResponse.subject: String?
    get() = (detail?.get("subject") as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Whether the app still shows the failed change as applied: nothing has put the old value back. */
val NotificationResponse.stillShownAsApplied: Boolean get() = revertedAt == null

data class AlertsState(
    val mailAlerts: List<AlertResponse> = emptyList(),
    val systemAlerts: List<AlertResponse> = emptyList(),
    /** Writes PostIMAP gave up on, listed under System beside the alerts. */
    val notifications: List<NotificationResponse> = emptyList(),
    /** Every account's name by id; a row names its account only when there is more than one. */
    val accountNames: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
) {
    /** Every non-mail kind present, which is what "dismiss all" on the system list sends. */
    val systemKinds: List<String> get() = systemAlerts.map { it.kind }.distinct()

    /** What the System tab counts and lists: the non-mail alerts and the write failures. */
    val systemCount: Int get() = systemAlerts.size + notifications.size

    /** The account a row names, or null while the person has only one and a chip would say nothing. */
    fun accountLabel(accountId: String): String? = if (accountNames.size > 1) accountNames[accountId] else null
}

/**
 * The alert list and the number on the bell. The badge is never computed here: `GET /alerts/badge`
 * is the one source, asked again whenever the server says alerts changed, with the registered
 * device's own scope when this phone has one ([subscriptionId]).
 */
class AlertsStore(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val subscriptionId: () -> String? = { null },
) {
    private val _state = MutableStateFlow(AlertsState())
    val state: StateFlow<AlertsState> = _state.asStateFlow()

    private val _badge = MutableStateFlow(0)
    val badge: StateFlow<Int> = _badge.asStateFlow()

    /** Reads the unseen alerts and unacknowledged notifications. Keeps what is on screen when a read fails, and says so. */
    suspend fun load() {
        _state.update { it.copy(isLoading = !it.hasLoaded, errorMessage = null) }
        try {
            val alerts = api.listAlerts(limit = LIST_LIMIT, unseenOnly = true)
            _state.update {
                it.copy(
                    mailAlerts = alerts.filter { a -> AlertKinds.isMail(a.kind) },
                    systemAlerts = alerts.filterNot { a -> AlertKinds.isMail(a.kind) },
                    isLoading = false,
                    hasLoaded = true,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { it.copy(isLoading = false, errorMessage = e.userMessage) }
        }
        loadNotifications()
        refreshBadge()
    }

    /**
     * 501 means the connected PostIMAP has no notification centre: nothing to list, nothing to report.
     * Any other failure keeps the rows on screen and says so.
     */
    private suspend fun loadNotifications() {
        try {
            val rows = api.listNotifications(unacknowledgedOnly = true, limit = NOTIFICATION_LIMIT)
            val names = if (rows.isEmpty()) emptyMap() else accountNames()
            _state.update { it.copy(notifications = rows, accountNames = names) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (e.statusCode == NOT_IMPLEMENTED) {
                _state.update { it.copy(notifications = emptyList()) }
            } else {
                _state.update { it.copy(errorMessage = e.userMessage) }
            }
        }
    }

    /** A chip is a nicety: without the names the rows simply carry none. */
    private suspend fun accountNames(): Map<String, String> =
        try {
            api.listAccounts().associate { it.id to it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            _state.value.accountNames
        }

    suspend fun refreshBadge() {
        try {
            _badge.value = api.getAlertBadge(subscriptionId()).count
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError) {
            // A bell that cannot be counted keeps its last number; the list says why when it is opened.
        }
    }

    suspend fun dismiss(alertId: String) {
        try {
            api.dismissAlert(alertId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { it.copy(errorMessage = e.userMessage) }
            return
        }
        _state.update {
            it.copy(
                mailAlerts = it.mailAlerts.filterNot { a -> a.id == alertId },
                systemAlerts = it.systemAlerts.filterNot { a -> a.id == alertId },
            )
        }
        refreshBadge()
    }

    /** Acknowledges one write failure; the server holds it per account and every device sees it go. */
    suspend fun acknowledge(notification: NotificationResponse) {
        try {
            api.acknowledgeNotification(notification.accountId, notification.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { it.copy(errorMessage = e.userMessage) }
            return
        }
        _state.update {
            it.copy(
                notifications =
                    it.notifications.filterNot { n ->
                        n.id == notification.id && n.accountId == notification.accountId
                    },
            )
        }
        refreshBadge()
    }

    suspend fun dismissAllMail() = dismissAll(listOf(AlertKinds.MAIL)) { it.copy(mailAlerts = emptyList()) }

    /** Clears the System tab: every non-mail alert and every write failure, one account at a time. */
    suspend fun dismissAllSystem() {
        for (accountId in _state.value.notifications
            .map { it.accountId }
            .distinct()) {
            if (!acknowledgeAll(accountId)) return
        }
        val kinds = _state.value.systemKinds
        if (kinds.isNotEmpty()) dismissAll(kinds) { it.copy(systemAlerts = emptyList()) } else refreshBadge()
    }

    private suspend fun acknowledgeAll(accountId: String): Boolean {
        try {
            api.acknowledgeAllNotifications(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { it.copy(errorMessage = e.userMessage) }
            return false
        }
        _state.update { it.copy(notifications = it.notifications.filterNot { n -> n.accountId == accountId }) }
        return true
    }

    private suspend fun dismissAll(
        kinds: List<String>,
        clear: (AlertsState) -> AlertsState,
    ) {
        try {
            api.dismissAllAlerts(kinds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { it.copy(errorMessage = e.userMessage) }
            return
        }
        _state.update(clear)
        refreshBadge()
    }

    /** Opening an alert reads it: dismisses it if unseen and says which message it is about, if any. */
    suspend fun open(alert: AlertResponse): String? {
        if (alert.dismissedAt == null) dismiss(alert.id)
        return alert.messageId
    }

    /**
     * `alert.new`, `alert.dismissed` and `notification.new` change the bell at once and the lists when
     * they have been opened. The stream announces an acknowledgement as `notification.new` too, so a
     * dismissal on another device empties the row here.
     */
    fun follow(events: Flow<List<LiveEvent>>) {
        scope.launch {
            events.collect { batch ->
                val stale =
                    batch.any {
                        it.invalidation is LiveInvalidation.AlertsChanged ||
                            it.invalidation is LiveInvalidation.NotificationsChanged ||
                            it.invalidation is LiveInvalidation.Resync
                    }
                if (!stale) return@collect
                if (_state.value.hasLoaded) load() else refreshBadge()
            }
        }
    }

    private companion object {
        const val LIST_LIMIT = 200

        /** The server's ceiling for one list. */
        const val NOTIFICATION_LIMIT = 500
        const val NOT_IMPLEMENTED = 501
    }
}
