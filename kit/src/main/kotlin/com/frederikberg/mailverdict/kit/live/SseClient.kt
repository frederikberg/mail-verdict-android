package com.frederikberg.mailverdict.kit.live

import com.frederikberg.mailverdict.kit.auth.CredentialRefreshing
import com.frederikberg.mailverdict.kit.auth.RefreshOutcome
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.RequestFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.random.Random

/** Every number that shapes how the event stream reconnects. */
data class SseTiming(
    val initialBackoffMs: Long = 2_000,
    val maxBackoffMs: Long = 30_000,
    /** The floor between two connection attempts, whatever asks for one. It bounds the request rate this client can ever put on the server, independently of whether the backoff behaves. */
    val minAttemptIntervalMs: Long = 1_000,
    /**
     * How long a stream has to carry traffic before it counts as healthy enough to reset the backoff.
     * A connection accepted and dropped at once is a failure however good its status line was, so the
     * response headers alone never reset it.
     */
    val healthyStreamMs: Long = 10_000,
    /** The server pings every 15 s; three missed pings and the stream is treated as dead. */
    val staleTimeoutMs: Long = 45_000,
    /** Fraction of the backoff the jitter can add or subtract, so clients that lost the connection together do not return together. */
    val jitterFraction: Double = 0.25,
) {
    /** How long a caller asking right now must still wait, given the time since the last attempt started. */
    fun remainingFloorMs(sinceLastAttemptMs: Long): Long =
        if (sinceLastAttemptMs in 0 until minAttemptIntervalMs) minAttemptIntervalMs - sinceLastAttemptMs else 0

    /** The next delay after a failed attempt: double, capped. */
    fun nextBackoffMs(currentMs: Long): Long = minOf(currentMs * 2, maxBackoffMs)

    /** Jitter applied to the value actually waited, not the one carried forward, so the doubling stays exact. */
    fun jittered(
        delayMs: Long,
        random: Random = Random.Default,
    ): Long {
        val spread = (delayMs * jitterFraction).toLong()
        return if (spread <= 0) delayMs else delayMs + random.nextLong(-spread, spread + 1)
    }

    fun isHealthy(openForMs: Long): Boolean = openForMs >= healthyStreamMs
}

/** What the stream reports. Invoked from the client's own scope, one at a time. */
interface SseCallbacks {
    /** A record other than the idle ping. */
    fun onRecord(record: SseRecord)

    /** Any bytes arrived, pings included: the connection is open and flowing. */
    fun onActivity() = Unit

    fun onConnected()

    /** The stream is not open: it ended, failed, or was closed on purpose. */
    fun onDisconnected()
}

/**
 * The account-wide live-update stream, `GET /api/events`.
 *
 * Deliberately generic: it hands every caller a raw [SseRecord] and never interprets event names,
 * so it does not change shape when the server's event catalogue grows ([LiveEventHub] owns that).
 *
 * All state lives in one coroutine that processes commands in order, so [connect], [pause],
 * [resume], [retryNow] and [disconnect] are safe from any thread and a connection storm is
 * structurally impossible: every attempt carries a generation number, an attempt whose generation
 * was superseded can neither deliver anything nor schedule a successor, and no two attempts start
 * closer together than [SseTiming.minAttemptIntervalMs].
 *
 * A refused stream (401, or the proxy's bare 403) renews the token once and reconnects with it; a
 * refusal of the renewed token, a rejected renewal, a disabled user, or a refusal with nothing to
 * renew it with, reports the end of the session through [onAuthenticationFailure] once and stops
 * retrying.
 */
class SseClient(
    private val scope: CoroutineScope,
    private val requests: RequestFactory,
    private val transport: SseTransport,
    private val callbacks: SseCallbacks,
    private val accountId: String? = null,
    private val credentialRefresher: CredentialRefreshing? = null,
    private val onAuthenticationFailure: (() -> Unit)? = null,
    private val timing: SseTiming = SseTiming(),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val jitter: (Long) -> Long = { timing.jittered(it) },
) {
    private sealed interface Command {
        data object Connect : Command

        data object Disconnect : Command

        data object Pause : Command

        data object Resume : Command

        data object RetryNow : Command

        data class Fire(
            val generation: Long,
        ) : Command

        data class Connected(
            val generation: Long,
        ) : Command

        data class Healthy(
            val generation: Long,
        ) : Command

        data class Ended(
            val generation: Long,
            val result: AttemptResult,
        ) : Command
    }

    private sealed interface AttemptResult {
        /** The server closed the stream. */
        data object Closed : AttemptResult

        /** The server could not be reached or answered something that is not a stream. */
        data object Failed : AttemptResult

        /** Nothing arrived for the stale timeout. */
        data object Stale : AttemptResult

        /** Overtaken by a newer decision while connecting; says nothing. */
        data object Superseded : AttemptResult

        /** The session is over: the user was disabled, or the credential cannot be renewed. */
        data object SessionEnded : AttemptResult

        /** The credential was refused and a renewal was asked for. */
        data class Renewed(
            val outcome: RefreshOutcome,
        ) : AttemptResult
    }

    private val inbox = Channel<Command>(Channel.UNLIMITED)

    // Owned by the command loop; the two volatile ones are also read by the attempt itself.
    private var started = false
    private var stopped = false
    private var paused = false
    private var attemptLive = false
    private var backoffMs = timing.initialBackoffMs
    private var lastAttemptAtMs: Long? = null
    private var attemptJob: Job? = null
    private var waitJob: Job? = null
    private var lastAttemptJob: Job? = null

    @Volatile private var generation = 0L

    /**
     * Whether the last refusal was answered with a renewal that no connection has proven yet. A second
     * refusal after one means the new token is refused too: the session is over, not something to renew again.
     */
    @Volatile private var renewedAfterRefusal = false

    /** The last record id seen, sent back as `Last-Event-ID` so the server replays what was missed, or answers `resync` when the gap is too large. */
    @Volatile private var lastEventId: String? = null

    /** How many connection attempts have started. Every one is a request at the server, so this is what a test asserts on when asking whether the client can be made to open more streams than it should. */
    @Volatile var attemptsStarted: Int = 0
        private set

    init {
        scope.launch { for (command in inbox) handle(command) }
    }

    /** Idempotent: asking for a stream that is open or scheduled leaves it alone, so a caller that asks repeatedly cannot cancel the connection it is asking for. */
    fun connect() = post(Command.Connect)

    /** Terminal: a client that was disconnected can never be reconnected. */
    fun disconnect() = post(Command.Disconnect)

    /** Closes the stream until [resume] without retrying, for an app going to the background. */
    fun pause() = post(Command.Pause)

    /** Reconnects with the backoff reset, replaying from the last event id. Still subject to the attempt floor. */
    fun resume() = post(Command.Resume)

    /** The network came back: a reconnect waiting out its backoff goes now. An open stream, a paused or stopped client is left alone. */
    fun retryNow() = post(Command.RetryNow)

    private fun post(command: Command) {
        inbox.trySend(command)
    }

    private fun handle(command: Command) {
        when (command) {
            Command.Connect -> {
                if (stopped) return
                started = true
                paused = false
                if (attemptLive || waitJob != null) return
                schedule(0)
            }
            Command.Disconnect -> {
                if (stopped) return
                stopped = true
                generation++
                cancelInFlight()
                callbacks.onDisconnected()
                inbox.close()
            }
            Command.Pause -> {
                if (stopped || !started || paused) return
                paused = true
                generation++
                cancelInFlight()
            }
            Command.Resume -> {
                if (stopped || !started || !paused) return
                paused = false
                backoffMs = timing.initialBackoffMs
                schedule(0)
            }
            Command.RetryNow -> {
                if (stopped || paused || attemptLive || waitJob == null) return
                backoffMs = timing.initialBackoffMs
                schedule(0)
            }
            is Command.Fire -> {
                if (command.generation != generation) return
                waitJob = null
                startAttempt(command.generation)
            }
            is Command.Connected -> if (command.generation == generation) renewedAfterRefusal = false
            is Command.Healthy -> if (command.generation == generation) backoffMs = timing.initialBackoffMs
            is Command.Ended -> {
                if (command.generation != generation) return
                attemptLive = false
                attemptJob = null
                onEnded(command.result)
            }
        }
    }

    private fun onEnded(result: AttemptResult) {
        when (result) {
            AttemptResult.Superseded -> Unit
            AttemptResult.Closed, AttemptResult.Failed, AttemptResult.Stale -> retryAfterBackoff()
            AttemptResult.SessionEnded -> endSession()
            is AttemptResult.Renewed ->
                when (result.outcome) {
                    RefreshOutcome.Refreshed -> {
                        renewedAfterRefusal = true
                        backoffMs = timing.initialBackoffMs
                        callbacks.onDisconnected()
                        schedule(0)
                    }
                    RefreshOutcome.Rejected -> endSession()
                    RefreshOutcome.Unavailable -> retryAfterBackoff()
                }
        }
    }

    /** Nothing a retry does can fix a refused session, so the stream reports it once and stays closed until asked to connect again. */
    private fun endSession() {
        callbacks.onDisconnected()
        onAuthenticationFailure?.invoke()
    }

    /** A superseded attempt speaks for nobody: its disconnect would contradict a newer attempt's state. */
    private fun retryAfterBackoff() {
        callbacks.onDisconnected()
        val wait = jitter(backoffMs)
        backoffMs = timing.nextBackoffMs(backoffMs)
        schedule(wait)
    }

    /** The one door every attempt goes through: supersedes whatever is in flight, applies the floor, starts exactly one successor. */
    private fun schedule(delayMs: Long) {
        if (stopped || paused) return
        val floor = lastAttemptAtMs?.let { timing.remainingFloorMs(clockMs() - it) } ?: 0
        val wait = maxOf(delayMs, floor)
        generation++
        val attempt = generation
        cancelInFlight()
        if (wait <= 0) {
            startAttempt(attempt)
            return
        }
        waitJob =
            scope.launch {
                delay(wait)
                inbox.trySend(Command.Fire(attempt))
            }
    }

    private fun startAttempt(attempt: Long) {
        if (stopped || paused || attempt != generation) return
        lastAttemptAtMs = clockMs()
        attemptLive = true
        attemptsStarted++
        // A superseded attempt is cancelled asynchronously; the successor waits for it to finish so two streams are never open at once.
        val predecessor = lastAttemptJob
        val job =
            scope.launch {
                predecessor?.join()
                val result = runAttempt(attempt)
                inbox.trySend(Command.Ended(attempt, result))
            }
        attemptJob = job
        lastAttemptJob = job
    }

    private fun cancelInFlight() {
        attemptLive = false
        waitJob?.cancel()
        waitJob = null
        attemptJob?.cancel()
        attemptJob = null
    }

    private suspend fun runAttempt(attempt: Long): AttemptResult {
        val request =
            try {
                requests
                    .build(
                        "/events",
                        query = listOfNotNull(accountId?.let { "account_id" to it }),
                        accept = "text/event-stream",
                    ).newBuilder()
                    .apply { lastEventId?.let { header("Last-Event-ID", it) } }
                    .build()
            } catch (e: RequestFactory.ConfigurationError) {
                return AttemptResult.Failed
            }
        val connection =
            try {
                transport.open(request)
            } catch (e: IOException) {
                return AttemptResult.Failed
            }
        connection.use { conn ->
            if (conn.status !in 200..299) return refusal(conn.status, conn.errorBody, RequestFactory.bearerOf(request))
            if (attempt != generation) return AttemptResult.Superseded
            inbox.trySend(Command.Connected(attempt))
            callbacks.onConnected()
            val openedAt = clockMs()
            var healthy = false
            val splitter = LineSplitter()
            val accumulator = SseEventAccumulator()
            while (true) {
                val read =
                    try {
                        withTimeoutOrNull(timing.staleTimeoutMs) { Chunk(conn.readChunk()) }
                    } catch (e: IOException) {
                        return AttemptResult.Failed
                    }
                if (read == null) return AttemptResult.Stale
                val bytes = read.bytes ?: return AttemptResult.Closed
                if (attempt != generation) return AttemptResult.Superseded
                // Any bytes prove the connection alive; the idle ping is filtered out below, so counting only records would tear down a healthy, quiet stream.
                callbacks.onActivity()
                if (!healthy && timing.isHealthy(clockMs() - openedAt)) {
                    healthy = true
                    inbox.trySend(Command.Healthy(attempt))
                }
                for (line in splitter.ingest(bytes)) {
                    val record = accumulator.ingest(line) ?: continue
                    if (record.name == HEARTBEAT) continue
                    record.id?.let { lastEventId = it }
                    callbacks.onRecord(record)
                }
            }
        }
    }

    private class Chunk(
        val bytes: ByteArray?,
    )

    /** Classifies a non-2xx answer: the stream cannot tell the proxy's bare 403 from the app's own, so the body decides. */
    private suspend fun refusal(
        status: Int,
        body: ByteArray,
        bearer: String?,
    ): AttemptResult {
        val error = ApiError.from(status, body)
        val refresher = credentialRefresher
        return when {
            error.isDisabled || error.isDeviceRevoked -> AttemptResult.SessionEnded
            !error.isAuthenticationFailure -> AttemptResult.Failed
            refresher == null || renewedAfterRefusal -> AttemptResult.SessionEnded
            else -> AttemptResult.Renewed(refresher.refresh(afterRejectionOf = bearer))
        }
    }

    companion object {
        /** The server's idle heartbeat. It proves the connection alive and carries nothing for a caller. */
        const val HEARTBEAT = "ping"
    }
}
