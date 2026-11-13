package com.frederikberg.mailverdict.kit.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * An invalidation and when its record arrived. A mail burst is held up to [LiveEventHub.COALESCE_MS]
 * before delivery, so delivery time alone cannot say whether a re-read began before or after the
 * change the event announces.
 */
data class LiveEvent(
    val invalidation: LiveInvalidation,
    val arrivedAtMs: Long,
)

/** What a list's own subtitle reads: "Connecting…" for [Reconnecting], "Offline" for [Disconnected]. */
enum class ConnectionState {
    /** Never connected, or closed on purpose (signed out). */
    Disconnected,

    /** The stream retries with capped backoff forever once connected, so losing it always becomes this and never [Disconnected]. */
    Reconnecting,

    Connected,
}

/**
 * Consumes `GET /api/events` over [SseClient] and publishes typed invalidations to every store:
 * [LiveInvalidation.from] is the one table from event name to what it invalidates.
 *
 * One instance for the whole app, never one per screen: the server's event ring has no notion of a
 * connection "for screen X", and a second stream buys nothing but a second reconnect cycle.
 *
 * Mail-burst events (`mail.new`, `.updated`, `.deleted`, `folder.synced`) are held and delivered at
 * most once every [COALESCE_MS]: an initial sync inserting hundreds of rows in a few seconds would
 * otherwise ask every open list to re-render hundreds of times. Everything else is delivered the
 * moment it arrives, since a badge or a settings refetch should never wait on a timer.
 *
 * [clientFactory] builds the stream around the hub's own callbacks, so the hub never sees
 * transport details.
 */
class LiveEventHub(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    clientFactory: (SseCallbacks) -> SseClient,
) {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * [connectionState] as a screen should show it: a reconnect that finishes within
     * [RECONNECTING_GRACE_MS] (launch, a return from the background, a proxy recycling the stream)
     * never surfaces, so "Connecting…" means the connection is genuinely struggling.
     */
    private val _visibleConnectionState = MutableStateFlow(ConnectionState.Connected)
    val visibleConnectionState: StateFlow<ConnectionState> = _visibleConnectionState.asStateFlow()

    private val _events = MutableSharedFlow<List<LiveEvent>>(extraBufferCapacity = EVENT_BUFFER)

    /** Every delivery, in order: an immediate event alone, a mail burst as however many coalesced into the window. */
    val events: SharedFlow<List<LiveEvent>> = _events.asSharedFlow()

    private val _pathRestored = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The device's network path came back; a store showing a saved copy asks the server again. */
    val pathRestored: SharedFlow<Unit> = _pathRestored.asSharedFlow()

    private val lock = Any()
    private val pending = mutableListOf<LiveEvent>()
    private var flushJob: Job? = null
    private var visibleStateJob: Job? = null
    private val deliveries = Channel<List<LiveEvent>>(Channel.UNLIMITED)

    /** Set by [disconnect]: the stream's own farewell callback must not move the state again. */
    private var closed = false

    private val client: SseClient =
        clientFactory(
            object : SseCallbacks {
                override fun onRecord(record: SseRecord) = handle(record)

                override fun onConnected() = setConnectionState(ConnectionState.Connected)

                override fun onDisconnected() = streamDisconnected()
            },
        )

    init {
        // One forwarder keeps deliveries in the order they were decided, whichever thread decided them.
        scope.launch { for (batch in deliveries) _events.emit(batch) }
    }

    fun connect() {
        synchronized(lock) { updateState(ConnectionState.Reconnecting) }
        client.connect()
    }

    /** Terminal: closes the stream for good (signed out). */
    fun disconnect() {
        synchronized(lock) { closed = true }
        client.disconnect()
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
            pending.clear()
            updateState(ConnectionState.Disconnected)
        }
    }

    /** The app went to the background: the stream closes instead of dying unobserved. `resume` replays what was missed. */
    fun pause() {
        synchronized(lock) {
            if (_connectionState.value == ConnectionState.Disconnected) return
            updateState(ConnectionState.Reconnecting, surface = false)
        }
        client.pause()
    }

    /** Back in the foreground: reconnect now, not after whatever backoff had accumulated. */
    fun resume() {
        synchronized(lock) {
            if (_connectionState.value == ConnectionState.Disconnected) return
            updateVisibleState()
        }
        client.resume()
    }

    /** The device's network path came back: a stream waiting out its backoff tries now, and every store hears of it. */
    fun networkPathRestored() {
        _pathRestored.tryEmit(Unit)
        if (_connectionState.value == ConnectionState.Reconnecting) client.retryNow()
    }

    private fun setConnectionState(state: ConnectionState) = synchronized(lock) { if (!closed) updateState(state) }

    private fun streamDisconnected() = synchronized(lock) { if (!closed) updateState(ConnectionState.Reconnecting) }

    private fun updateState(
        state: ConnectionState,
        surface: Boolean = true,
    ) {
        _connectionState.value = state
        if (surface) updateVisibleState()
    }

    private fun updateVisibleState() {
        val state = _connectionState.value
        if (state != ConnectionState.Reconnecting) {
            visibleStateJob?.cancel()
            visibleStateJob = null
            _visibleConnectionState.value = state
            return
        }
        // A grace period already running is not restarted by every failed attempt, or a stream retrying faster than the period would never surface.
        if (_visibleConnectionState.value == ConnectionState.Reconnecting || visibleStateJob?.isActive == true) return
        if (_visibleConnectionState.value ==
            ConnectionState.Disconnected
        ) {
            _visibleConnectionState.value = ConnectionState.Connected
        }
        visibleStateJob =
            scope.launch {
                delay(RECONNECTING_GRACE_MS)
                if (_connectionState.value ==
                    ConnectionState.Reconnecting
                ) {
                    _visibleConnectionState.value = ConnectionState.Reconnecting
                }
            }
    }

    private fun handle(record: SseRecord) {
        val event = LiveEvent(LiveInvalidation.from(record), nowMs())
        when (event.invalidation) {
            is LiveInvalidation.MailNew,
            is LiveInvalidation.MailUpdated,
            is LiveInvalidation.MailDeleted,
            is LiveInvalidation.FolderSynced,
            ->
                synchronized(lock) {
                    pending += event
                    if (flushJob == null) {
                        flushJob =
                            scope.launch {
                                delay(COALESCE_MS)
                                flush()
                            }
                    }
                }
            else -> deliveries.trySend(listOf(event))
        }
    }

    private fun flush() {
        val batch =
            synchronized(lock) {
                flushJob = null
                pending.toList().also { pending.clear() }
            }
        if (batch.isNotEmpty()) deliveries.trySend(batch)
    }

    companion object {
        const val COALESCE_MS: Long = 500
        const val RECONNECTING_GRACE_MS: Long = 3_000
        private const val EVENT_BUFFER = 256
    }
}
