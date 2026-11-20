package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.contract.PendingSendResponse
import com.frederikberg.mailverdict.kit.format.MailFormat
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UndoOutcome {
    /** Cancelled in time: open the composer with this intent to get everything back. */
    data class Restored(
        val intent: ComposeIntent,
    ) : UndoOutcome

    data object TooLate : UndoOutcome

    data object Ignored : UndoOutcome
}

data class UndoSendState(
    val pending: List<PendingSendResponse> = emptyList(),
    val cancelling: Set<String> = emptySet(),
)

/**
 * Sends held inside the server's undo window, for the undo-send capsule. Rows come from a submit's
 * own answer and from `GET /outbox/pending` on start and whenever the server says the outbox changed.
 */
class UndoSendStore(
    private val backend: UndoSendBackend,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(UndoSendState())
    val state: StateFlow<UndoSendState> = _state.asStateFlow()
    private val restorations = HashMap<String, UndoRestoration>()

    suspend fun refresh() {
        val rows =
            try {
                backend.pending()
            } catch (_: ApiError) {
                return
            }
        _state.value = _state.value.copy(pending = rows)
    }

    fun add(row: PendingSendResponse) {
        val current = _state.value
        _state.value = current.copy(pending = current.pending.filter { it.id != row.id } + row)
    }

    /** A send leaving its window, or cancelled from another device, is an `outbox.updated`; a resync may have missed one. */
    fun follow(events: Flow<List<LiveEvent>>) {
        scope.launch {
            events.collect { batch ->
                val affected =
                    batch.any {
                        it.invalidation is LiveInvalidation.OutboxUpdated ||
                            it.invalidation == LiveInvalidation.Resync
                    }
                if (affected) refresh()
            }
        }
    }

    /** The rows still to show: a row stays [EXPIRY_GRACE_MS] past its countdown while the server sends it, then it has gone out. */
    fun visibleRows(nowMs: Long = this.nowMs()): List<PendingSendResponse> =
        _state.value.pending
            .filter { (sendAfterMs(it) ?: 0) + EXPIRY_GRACE_MS > nowMs }
            .sortedBy { sendAfterMs(it) }

    fun secondsRemaining(
        row: PendingSendResponse,
        nowMs: Long = this.nowMs(),
    ): Long {
        val left = (sendAfterMs(row) ?: return 0) - nowMs
        return if (left <= 0) 0 else (left + 999) / 1000
    }

    /** Cancels the send, then fetches back every staged file. A refused cancel reads as too late: by then the message has left. */
    suspend fun undo(id: String): UndoOutcome {
        val row = _state.value.pending.firstOrNull { it.id == id } ?: return UndoOutcome.Ignored
        if (id in _state.value.cancelling) return UndoOutcome.Ignored
        _state.value = _state.value.copy(cancelling = _state.value.cancelling + id)
        try {
            try {
                backend.cancel(id)
            } catch (_: ApiError) {
                return UndoOutcome.TooLate
            }
            val attachments = mutableListOf<ComposeAttachment>()
            val missing = mutableListOf<String>()
            for (summary in row.attachments) {
                val name = summary.filename ?: "attachment"
                try {
                    val raw = backend.pendingAttachment(id, summary.id)
                    attachments += ComposeAttachment(name, summary.contentType ?: raw.contentType, raw.data)
                } catch (_: ApiError) {
                    missing += name
                }
            }
            synchronized(restorations) { restorations[id] = UndoRestoration(row, attachments, missing) }
            return UndoOutcome.Restored(ComposeIntent(ComposeIntent.Kind.UndoRestore(id)))
        } finally {
            val current = _state.value
            _state.value =
                current.copy(pending = current.pending.filter { it.id != id }, cancelling = current.cancelling - id)
        }
    }

    /** What the composer reopening a cancelled send starts from; kept until [forget], since a screen may be built more than once. */
    fun restoration(
        pendingSendId: String,
    ): UndoRestoration? = synchronized(restorations) { restorations[pendingSendId] }

    fun forget(pendingSendId: String) {
        synchronized(restorations) { restorations.remove(pendingSendId) }
    }

    private fun sendAfterMs(row: PendingSendResponse): Long? = MailFormat.parseInstant(row.sendAfter)?.toEpochMilli()

    companion object {
        const val EXPIRY_GRACE_MS = 2_000L
    }
}
