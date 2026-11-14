package com.frederikberg.mailverdict.kit.alerts

import com.frederikberg.mailverdict.contract.AlertResponse
import com.frederikberg.mailverdict.kit.api.dismissAlert
import com.frederikberg.mailverdict.kit.api.dismissAllAlerts
import com.frederikberg.mailverdict.kit.api.getAlertBadge
import com.frederikberg.mailverdict.kit.api.listAlerts
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

/** Which of the two lists an alert belongs to: new mail, or everything else the server raises. */
object AlertKinds {
    const val MAIL = "mail"

    fun isMail(kind: String) = kind == MAIL
}

data class AlertsState(
    val mailAlerts: List<AlertResponse> = emptyList(),
    val systemAlerts: List<AlertResponse> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
) {
    /** Every non-mail kind present, which is what "dismiss all" on the system list sends. */
    val systemKinds: List<String> get() = systemAlerts.map { it.kind }.distinct()
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

    /** Reads the unseen alerts. Keeps what is on screen when the read fails, and says so. */
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
        refreshBadge()
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

    suspend fun dismissAllMail() = dismissAll(listOf(AlertKinds.MAIL)) { it.copy(mailAlerts = emptyList()) }

    suspend fun dismissAllSystem() {
        val kinds = _state.value.systemKinds
        if (kinds.isNotEmpty()) dismissAll(kinds) { it.copy(systemAlerts = emptyList()) }
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

    /** `alert.new` and `alert.dismissed` change the bell at once and the list when it has been opened. */
    fun follow(events: Flow<List<LiveEvent>>) {
        scope.launch {
            events.collect { batch ->
                val stale =
                    batch.any {
                        it.invalidation is LiveInvalidation.AlertsChanged || it.invalidation is LiveInvalidation.Resync
                    }
                if (!stale) return@collect
                if (_state.value.hasLoaded) load() else refreshBadge()
            }
        }
    }

    private companion object {
        const val LIST_LIMIT = 200
    }
}
