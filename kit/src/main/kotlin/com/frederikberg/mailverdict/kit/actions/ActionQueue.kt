package com.frederikberg.mailverdict.kit.actions

import com.frederikberg.mailverdict.contract.BulkActionRequest
import com.frederikberg.mailverdict.contract.MessageSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

/** Something holding server data the queue's intents are projected over: a list, the reader. */
interface IntentObserver {
    /** Rows this holds for [messageIds], kept on a new intent so an undone move can put them back. */
    fun intentSnapshots(messageIds: Set<String>): List<MessageSummary> = emptyList()

    /** These done intents are leaving the queue: data read before they settled keeps their effect. */
    fun intentsWillRetire(intents: List<MailIntent>) = Unit

    /** These intents reached the server, or found their message gone. */
    fun intentsDidSettle(intents: List<MailIntent>) = Unit
}

/** Whether a request can leave the device. */
interface QueueConnectivity {
    val online: StateFlow<Boolean>
}

object AlwaysOnlineQueue : QueueConnectivity {
    override val online: StateFlow<Boolean> = MutableStateFlow(true)
}

/** Everything the screens read: the intents, applied over server data by [IntentProjection]. */
data class QueueState(
    /** Creation order. Open intents apply; done ones are kept until retired. */
    val intents: List<MailIntent> = emptyList(),
    /** Bumped whenever an intent settles. Data records it when its read begins ([IntentProjection.applies]). */
    val sequence: Int = 0,
    /** Open intents that have been waiting long enough to say so. */
    val waitingIds: Set<String> = emptySet(),
    /** Until when a refused credential or login proxy holds delivery. */
    val heldUntilMs: Long? = null,
) {
    val failed: List<MailIntent> get() = intents.filter { it.state == IntentPhase.Failed }
    val hasOpen: Boolean get() = intents.any { it.isOpen }
}

/**
 * Every mail action between a person and the server: durable, delivered in order, retried and
 * undoable. One instance per connection, shared by every list and the reader.
 *
 * Each account drains serially: one request out at a time, in the order the actions were taken, and
 * never an intent ahead of an earlier one touching the same message, so a mark-read and an archive
 * of one message always arrive in that order.
 *
 * - A request that gets no answer, a timeout, a 429 or a 5xx is retried with backoff, up to
 *   [Timing.maxAttempts]; then it fails visibly. Before retrying one that may already have been
 *   applied, the messages are read and an action they already show counts as done.
 * - While the device is offline nothing is sent; everything goes the moment it is back.
 * - A 401, 403 or login-proxy answer holds everything, unsent and unfailed, for [Timing.holdRecheckMs]
 *   or until the app returns to the foreground.
 * - A 503 is the server still applying an earlier attempt: asked again shortly, never counted.
 * - Any other 4xx refuses the intent; a 404 means the message is gone and the intent retires.
 */
class ActionQueue(
    private val transport: IntentTransport,
    private val persistence: IntentPersistence,
    private val scope: CoroutineScope,
    private val connectivity: QueueConnectivity = AlwaysOnlineQueue,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val timing: Timing = Timing(),
) {
    data class Timing(
        /** How long a request may be out before its rows say they are waiting. */
        val waitingAfterMs: Long = 800,
        val backoffBaseMs: Long = 1_000,
        val backoffCapMs: Long = 60_000,
        /** Attempts before a retrying intent fails and asks for Retry or Discard. */
        val maxAttempts: Int = 8,
        /** How long a done intent stays: it stops applying the moment data read after it lands, but stays this long to be undone. */
        val doneRetentionMs: Long = 90_000,
        /** How long after an intent was answered an event for one of its messages still counts as its echo: the event and the response race each other. */
        val echoGraceMs: Long = 1_500,
        /** How long a failed intent keeps marking its rows and offering Retry. */
        val failedRetentionMs: Long = 86_400_000,
        /** Past this many messages an intent that may have landed is sent again without reading each message first; the idempotency key alone keeps that safe. */
        val reconcileLimit: Int = 25,
        /** How soon a request answered 503 is asked again. */
        val busyRetryMs: Long = 5_000,
        /** How long a refused credential holds delivery before it is tried again. */
        val holdRecheckMs: Long = 60_000,
    )

    private val lock = Any()
    private var intents: List<MailIntent> = emptyList()
    private var sequence = 0
    private var waitingIds: Set<String> = emptySet()
    private var heldUntil: Long? = null
    private var stopped = false
    private val workers = HashMap<String, Job>()
    private val wakers = HashMap<String, CompletableDeferred<Unit>>()
    private val callbacks = HashMap<String, (IntentOutcome) -> Unit>()
    private val observers = java.util.concurrent.CopyOnWriteArrayList<IntentObserver>()
    private var retirement: Job? = null
    private var roleFolders = HashMap<String, Map<String, String>>()

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<QueueNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<QueueNotice> = _notices.asSharedFlow()

    init {
        // What the last run left. A request that was out may have landed, so it is checked before being sent again; one undone while out is reversed; a done one is dropped, since every read from now on already has it.
        val loaded =
            persistence.load().mapNotNull {
                when (it.state) {
                    IntentPhase.Done -> null
                    IntentPhase.Sending -> it.copy(state = IntentPhase.Pending, mayHaveLanded = true)
                    else -> it
                }
            }
        synchronized(lock) {
            intents = loaded
            loaded.filter { it.undoRequested && it.isOpen }.forEach { replaceWithReversalLocked(it.id) }
            changedLocked()
        }
        scope.launch { connectivity.online.collect { if (it) resume() } }
        resume()
        scheduleRetirement()
    }

    fun addObserver(observer: IntentObserver) {
        observers.addIfAbsent(observer)
    }

    fun removeObserver(observer: IntentObserver) {
        observers.remove(observer)
    }

    // Reading

    /**
     * Whether an event announcing a change to [messageId], which arrived at [arrivedAtMs], is the echo
     * of an intent that has since been answered: it arrived after that intent's request left and no
     * later than the grace after the answer. Such a change is in whatever the answer's own re-read
     * reads, so a screen that re-reads on settling need not read again.
     */
    fun isSettledEcho(
        messageId: String,
        accountId: String,
        arrivedAtMs: Long,
    ): Boolean =
        synchronized(lock) {
            intents.any { intent ->
                val sent = intent.sentAtMs
                val settled = intent.settledAtMs
                intent.state == IntentPhase.Done &&
                    intent.accountId == accountId &&
                    sent != null &&
                    settled != null &&
                    arrivedAtMs >= sent &&
                    arrivedAtMs <= settled + timing.echoGraceMs &&
                    (messageId in touched(intent) || intent.movedSources.any { it.id == messageId })
            }
        }

    fun rowState(messageId: String): IntentRowState = rowState(_state.value, messageId)

    /** Messages an intent has taken out of their folder and no later one has moved back. */
    val hiddenMessageIds: Set<String>
        get() =
            synchronized(lock) {
                val hidden = mutableSetOf<String>()
                for (intent in intents) {
                    if (!intent.leavesFolder || !IntentProjection.applies(intent, 0)) continue
                    if (intent.undoes == null) hidden += intent.messageIds else hidden -= intent.messageIds.toSet()
                }
                hidden
            }

    // Acting

    /** Records an action and starts delivering it; [undoNotice] is offered at once with Undo. [onSettled] runs once, when the intent is done, gone, refused or cancelled. */
    fun enqueue(
        request: IntentRequest,
        undoNotice: String? = null,
        onSettled: ((IntentOutcome) -> Unit)? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        if (stopped) return id
        var snapshots = request.snapshots
        if (snapshots.isEmpty()) {
            val wanted = request.messageIds.toSet()
            snapshots =
                observers
                    .asSequence()
                    .map { it.intentSnapshots(wanted) }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
        }
        val intent =
            MailIntent(
                id = id,
                accountId = request.accountId,
                action = request.action,
                targetFolderId = request.targetFolderId,
                messageIds = request.messageIds,
                delivery = request.delivery,
                originFolderIds = request.originFolderIds,
                snapshots = snapshots,
                confirm = request.confirm,
                createdAtMs = nowMs(),
                seenThrough = request.seenThrough,
            )
        synchronized(lock) {
            intents = intents + intent
            onSettled?.let { callbacks[id] = it }
            changedLocked()
        }
        wake(intent.accountId)
        if (undoNotice != null) _notices.tryEmit(QueueNotice.Undo(undoNotice, listOf(id)))
        return id
    }

    /**
     * Undoes intents. One that never left is dropped. One that may have reached the server (done, or
     * attempted without an answer) is reversed by a new intent moving each message back, or flipping
     * read and star back: a move to where a message already is changes nothing, so that is safe either
     * way. One whose request is out stops applying at once and is reversed once the request settles.
     */
    fun undo(ids: List<String>) {
        if (stopped) return
        val cancelled = mutableListOf<String>()
        val accounts = mutableSetOf<String>()
        synchronized(lock) {
            for (id in ids) {
                val intent = intents.firstOrNull { it.id == id } ?: continue
                accounts += intent.accountId
                when (intent.state) {
                    IntentPhase.Pending ->
                        if (intent.attempts == 0 && !intent.mayHaveLanded) {
                            intents = intents.filterNot { it.id == id }
                            waitingIds = waitingIds - id
                            cancelled += id
                        } else {
                            replaceWithReversalLocked(id)
                        }
                    IntentPhase.Sending -> replace(id) { it.copy(undoRequested = true) }
                    IntentPhase.Done -> appendReversalLocked(intent)
                    IntentPhase.Failed ->
                        if (intent.mayHaveLanded) {
                            replaceWithReversalLocked(id)
                        } else {
                            intents = intents.filterNot { it.id == id }
                            cancelled += id
                        }
                }
            }
            changedLocked()
        }
        cancelled.forEach { finish(it, IntentOutcome.Cancelled) }
        accounts.forEach(::wake)
    }

    /** Tells the person [message] with an Undo for [ids]. */
    fun offerUndo(
        message: String,
        ids: List<String>,
    ) {
        _notices.tryEmit(QueueNotice.Undo(message, ids))
    }

    /** Failed intents given up on: undone, including whatever of them may have landed. */
    fun discard(ids: List<String>) = undo(ids)

    /** Sends failed intents again from scratch. */
    fun retry(ids: List<String>) {
        if (stopped) return
        synchronized(lock) {
            intents =
                intents.map {
                    if (it.id in ids && it.state == IntentPhase.Failed) {
                        it.copy(
                            state = IntentPhase.Pending,
                            attempts = 0,
                            nextAttemptAtMs = null,
                            lastError = null,
                            settledSequence = null,
                            settledAtMs = null,
                        )
                    } else {
                        it
                    }
                }
            changedLocked()
        }
        resume()
    }

    /** Back in the foreground or back online: whatever is waiting on a backoff goes now. */
    fun resume() {
        if (stopped) return
        val accounts =
            synchronized(lock) {
                heldUntil = null
                intents = intents.map { if (it.state == IntentPhase.Pending) it.copy(nextAttemptAtMs = null) else it }
                intents.filter { it.isOpen }.map { it.accountId }.toSet()
            }
        accounts.forEach(::wake)
    }

    /** Ends delivery for good: the connection this queue belongs to is going away. Nothing is written after this; open intents stay as persisted. */
    fun stop() {
        synchronized(lock) {
            stopped = true
            workers.values.forEach { it.cancel() }
            workers.clear()
            retirement?.cancel()
        }
    }

    /** Returns once nothing is left to send. */
    suspend fun waitUntilIdle() {
        _state.first { !it.hasOpen }
    }

    // Delivery

    private fun wake(accountId: String) {
        synchronized(lock) {
            if (stopped) return
            wakers[accountId]?.complete(Unit)
            if (workers[accountId]?.isActive == true) return
            workers[accountId] = scope.launch { drain(accountId) }
        }
    }

    private sealed interface Step {
        data class Send(
            val id: String,
        ) : Step

        /** Until [wake] fires, or [ms] has passed (forever when null). */
        data class Wait(
            val ms: Long?,
            val wake: CompletableDeferred<Unit>,
        ) : Step
    }

    private suspend fun drain(accountId: String) {
        while (true) {
            val step =
                synchronized(lock) {
                    computeStepLocked(accountId).also { if (it == null) workers.remove(accountId) }
                } ?: return
            when (step) {
                is Step.Send -> send(step.id)
                is Step.Wait ->
                    if (step.ms ==
                        null
                    ) {
                        step.wake.await()
                    } else {
                        withTimeoutOrNull(step.ms) { step.wake.await() }
                    }
            }
        }
    }

    private fun computeStepLocked(accountId: String): Step? {
        if (stopped) return null
        val open = intents.filter { it.accountId == accountId && it.isOpen }
        if (open.isEmpty()) return null
        val now = nowMs()
        val wake = CompletableDeferred<Unit>().also { wakers[accountId] = it }
        heldUntil?.let {
            if (it > now) return Step.Wait(it - now, wake)
            heldUntil = null
            publishLocked()
        }
        if (!connectivity.online.value) {
            waitingIds = waitingIds + open.map { it.id }
            publishLocked()
            return Step.Wait(null, wake)
        }
        val blocked = mutableSetOf<String>()
        var earliest: Long? = null
        for (intent in open) {
            if (intent.state == IntentPhase.Pending && blocked.none { it in touched(intent) }) {
                val due = intent.nextAttemptAtMs
                if (due == null || due <= now) return Step.Send(intent.id)
                earliest = min(earliest ?: due, due)
            }
            blocked += touched(intent)
        }
        return Step.Wait(earliest?.let { it - now }, wake)
    }

    private suspend fun send(id: String) {
        val intent =
            synchronized(lock) {
                if (intents.none { it.id == id }) return
                replace(id) { it.copy(state = IntentPhase.Sending, attempts = it.attempts + 1, sentAtMs = nowMs()) }
                changedLocked()
                intents.first { it.id == id }
            }
        val marker =
            scope.launch {
                delay(timing.waitingAfterMs)
                synchronized(lock) {
                    if (intents.any { it.id == id && it.isOpen }) {
                        waitingIds = waitingIds + id
                        publishLocked()
                    }
                }
            }
        var result: IntentDelivery? = null
        if (intent.mayHaveLanded) result = reconcile(intent)
        if (result == null) result = deliver(intent)
        marker.cancel()
        if (stopped) return
        settle(id, result)
    }

    private suspend fun deliver(intent: MailIntent): IntentDelivery =
        try {
            var sending = intent
            if (intent.undoes != null && intent.leavesFolder) {
                sending =
                    guardReversal(intent)
                        ?: return IntentDelivery.Refused("There is no folder the action could have left the message in")
            }
            when (val delivery = sending.delivery) {
                Delivery.Message -> {
                    val messageId = sending.messageIds.first()
                    val response =
                        transport.deliverMessageAction(
                            messageId,
                            sending.action,
                            sending.targetFolderId,
                            sending.expectedFolderIds?.get(messageId),
                            sending.id,
                            sending.confirm,
                        )
                    when {
                        !response.success -> IntentDelivery.Refused(response.message ?: "The server did not apply it")
                        !response.applied -> IntentDelivery.NotApplied
                        else ->
                            IntentDelivery.Delivered(
                                filed = response.folderId?.let { mapOf(messageId to it) }.orEmpty(),
                            )
                    }
                }
                is Delivery.Bulk ->
                    IntentDelivery.of(
                        transport.deliverBulkAction(
                            sending.accountId,
                            BulkActionRequest(
                                action = sending.action.toBulk(),
                                targetFolderId = sending.targetFolderId,
                                ids = sending.messageIds,
                                expandThreads = delivery.expandThreads,
                                confirm = sending.confirm,
                                idempotencyKey = sending.id,
                                expectedFolderIds = sending.expectedFolderIds,
                                expandThreadsThrough = if (delivery.expandThreads) sending.seenThrough else null,
                            ),
                        ),
                        sending,
                    )
            }
        } catch (e: Throwable) {
            IntentDelivery.classify(e)
        }

    /** An undo never moves a message it cannot say where it expects to find: each message not already known to be filed somewhere is expected in the folder the reversed action files into. Null when that folder does not exist. */
    private suspend fun guardReversal(intent: MailIntent): MailIntent? {
        val missing = intent.messageIds.filter { intent.originFolderIds[it] == null }
        if (missing.isEmpty()) return intent
        val action = intent.reversedAction ?: return null
        val landing = landingFolder(action, intent.reversedTargetFolderId, intent.accountId) ?: return null
        val guarded = intent.copy(originFolderIds = intent.originFolderIds + missing.associateWith { landing })
        synchronized(lock) {
            replace(intent.id) { it.copy(originFolderIds = guarded.originFolderIds) }
            changedLocked()
        }
        return guarded
    }

    /** The folder [action] files a message into: a move's own target, or the account's folder for that role. Null for an action that files nowhere, or a role the account has no folder for. */
    private suspend fun landingFolder(
        action: TriageAction,
        target: String?,
        accountId: String,
    ): String? {
        val role =
            when (action) {
                TriageAction.Move -> return target
                TriageAction.Archive -> "archive"
                TriageAction.Trash -> "trash"
                TriageAction.Spam -> "junk"
                TriageAction.NotSpam -> "inbox"
                else -> return null
            }
        roleFolders[accountId]?.let { return it[role] }
        val byRole = HashMap<String, String>()
        for (folder in transport.fetchFolders(accountId)) {
            val use = folder.specialUse
            if (use != null && use !in byRole) byRole[use] = folder.id
        }
        roleFolders[accountId] = byRole
        return byRole[role]
    }

    /**
     * For an intent that may already have been applied: delivered when every message already shows it
     * (a moved message in the folder the action files into, not merely gone from where it was, since
     * someone else may have moved it), null (send it) otherwise, or the lookup's own failure.
     */
    private suspend fun reconcile(intent: MailIntent): IntentDelivery? {
        val ids = intent.messageIds
        if (ids.size > timing.reconcileLimit || intent.action == TriageAction.Expunge) return null
        return try {
            var landing: String? = null
            if (intent.leavesFolder) {
                val action = if (intent.undoes == null) intent.action else TriageAction.Move
                landing = landingFolder(action, intent.targetFolderId, intent.accountId) ?: return null
            }
            for (id in ids) {
                val state = transport.fetchMessageState(id, includeFlags = !intent.leavesFolder)
                if (!shows(intent, state, landing)) return null
            }
            IntentDelivery.Delivered(filed = landing?.let { folder -> ids.associateWith { folder } }.orEmpty())
        } catch (e: Throwable) {
            when (val result = IntentDelivery.classify(e)) {
                is IntentDelivery.Hold -> result
                is IntentDelivery.Retry -> IntentDelivery.Retry(result.reason, true)
                is IntentDelivery.Refused -> IntentDelivery.Retry(result.reason, true)
                is IntentDelivery.Busy -> IntentDelivery.Retry(result.reason, true)
                else -> null
            }
        }
    }

    private fun settle(
        id: String,
        result: IntentDelivery,
    ) {
        var outcome: IntentOutcome? = null
        var settled: MailIntent? = null
        var notice: QueueNotice? = null
        synchronized(lock) {
            val index = intents.indexOfFirst { it.id == id }
            if (index < 0) return
            val intent = intents[index]
            when (result) {
                is IntentDelivery.Delivered -> {
                    sequence += 1
                    val done =
                        intent.copy(
                            state = IntentPhase.Done,
                            settledSequence = sequence,
                            settledAtMs = nowMs(),
                            movedSources = result.sources,
                            filedFolderIds = result.filed,
                            lastError = null,
                            mayHaveLanded = false,
                            undoRequested = false,
                        )
                    replace(id) { done }
                    waitingIds = waitingIds - id
                    if (intent.undoRequested) appendReversalLocked(done)
                    settled = done
                    outcome =
                        if (intent.undoRequested) {
                            IntentOutcome.Cancelled
                        } else {
                            IntentOutcome.Done(
                                result.affectedCount,
                                result.sources,
                            )
                        }
                }
                IntentDelivery.Gone, IntentDelivery.NotApplied -> {
                    sequence += 1
                    intents = intents.filterNot { it.id == id }
                    waitingIds = waitingIds - id
                    settled = intent
                    if (result == IntentDelivery.NotApplied) {
                        notice =
                            QueueNotice.Info(
                                if (intent.undoes != null) {
                                    "Nothing to undo: the message is no longer where the action left it"
                                } else {
                                    "Did not ${intent.action.phrase}: the message had already moved"
                                },
                            )
                    }
                    outcome = if (result == IntentDelivery.Gone) IntentOutcome.Gone else IntentOutcome.NotApplied
                }
                is IntentDelivery.Retry -> {
                    val landed = intent.mayHaveLanded || result.mayHaveLanded
                    when {
                        intent.undoRequested -> {
                            replace(
                                id,
                            ) {
                                it.copy(
                                    state = IntentPhase.Pending,
                                    lastError = result.reason,
                                    mayHaveLanded = landed,
                                )
                            }
                            replaceWithReversalLocked(id)
                        }
                        intent.attempts >= timing.maxAttempts -> {
                            val reason = "Still failing after ${intent.attempts} attempts: ${result.reason}"
                            outcome = failLocked(id, reason, landed)
                            notice = failureNotice(intent, reason)
                        }
                        else -> {
                            val exponent = (intent.attempts - 1).coerceAtLeast(0).toDouble()
                            val delayMs =
                                min(
                                    timing.backoffBaseMs * 2.0.pow(exponent),
                                    timing.backoffCapMs.toDouble(),
                                ).toLong()
                            replace(id) {
                                it.copy(
                                    state = IntentPhase.Pending,
                                    lastError = result.reason,
                                    mayHaveLanded = landed,
                                    nextAttemptAtMs = nowMs() + delayMs,
                                )
                            }
                            waitingIds = waitingIds + id
                        }
                    }
                }
                is IntentDelivery.Busy -> {
                    replace(id) {
                        it.copy(
                            state = IntentPhase.Pending,
                            attempts = (it.attempts - 1).coerceAtLeast(0),
                            lastError = result.reason,
                            nextAttemptAtMs = nowMs() + timing.busyRetryMs,
                        )
                    }
                    waitingIds = waitingIds + id
                    if (intent.undoRequested) replaceWithReversalLocked(id)
                }
                is IntentDelivery.Hold -> {
                    replace(id) {
                        it.copy(
                            state = IntentPhase.Pending,
                            attempts = (it.attempts - 1).coerceAtLeast(0),
                            lastError = result.reason,
                        )
                    }
                    waitingIds = waitingIds + id
                    heldUntil = nowMs() + timing.holdRecheckMs
                    if (intent.undoRequested) replaceWithReversalLocked(id)
                }
                is IntentDelivery.Refused ->
                    when {
                        intent.undoRequested && !intent.mayHaveLanded -> {
                            intents = intents.filterNot { it.id == id }
                            waitingIds = waitingIds - id
                            outcome = IntentOutcome.Cancelled
                        }
                        intent.undoRequested -> {
                            replace(id) { it.copy(state = IntentPhase.Pending) }
                            replaceWithReversalLocked(id)
                        }
                        else -> {
                            outcome = failLocked(id, result.reason, intent.mayHaveLanded)
                            notice = failureNotice(intent, result.reason)
                        }
                    }
            }
            changedLocked()
        }
        settled?.let { s -> observers.forEach { it.intentsDidSettle(listOf(s)) } }
        notice?.let { _notices.tryEmit(it) }
        outcome?.let { finish(id, it) }
        scheduleRetirement()
    }

    private fun failLocked(
        id: String,
        reason: String,
        mayHaveLanded: Boolean,
    ): IntentOutcome {
        sequence += 1
        replace(id) {
            it.copy(
                state = IntentPhase.Failed,
                settledSequence = sequence,
                settledAtMs = nowMs(),
                lastError = reason,
                mayHaveLanded = mayHaveLanded,
            )
        }
        waitingIds = waitingIds - id
        return IntentOutcome.Failed(reason)
    }

    private fun failureNotice(
        intent: MailIntent,
        reason: String,
    ) = QueueNotice.Failure(
        if (intent.undoes != null) "Could not undo: $reason" else "Could not ${intent.action.phrase}: $reason",
        intent.id,
    )

    private fun finish(
        id: String,
        outcome: IntentOutcome,
    ) {
        synchronized(lock) { callbacks.remove(id) }?.invoke(outcome)
    }

    // Undo

    /** Removes an intent that may have reached the server and puts its reversal in its place. Must be called with [lock] held. */
    private fun replaceWithReversalLocked(id: String) {
        val intent = intents.firstOrNull { it.id == id } ?: return
        intents = intents.filterNot { it.id == id }
        waitingIds = waitingIds - id
        appendReversalLocked(intent)
        callbacks.remove(id)?.let { cb -> scope.launch { cb(IntentOutcome.Cancelled) } }
    }

    private fun appendReversalLocked(intent: MailIntent) {
        for (request in reversal(intent)) {
            val undo =
                MailIntent(
                    id = UUID.randomUUID().toString(),
                    accountId = request.accountId,
                    action = request.action,
                    targetFolderId = request.targetFolderId,
                    messageIds = request.messageIds,
                    delivery = request.delivery,
                    originFolderIds = request.originFolderIds,
                    snapshots = request.snapshots,
                    undoes = intent.id,
                    createdAtMs = nowMs(),
                    reversedAction = if (intent.leavesFolder) intent.action else null,
                    reversedTargetFolderId = if (intent.leavesFolder) intent.targetFolderId else null,
                )
            intents = intents + undo
        }
    }

    // Retirement

    private fun scheduleRetirement() {
        synchronized(lock) {
            retirement?.cancel()
            if (stopped) return
            val next = intents.mapNotNull(::retirementDeadline).minOrNull() ?: return
            val wait = (next - nowMs()).coerceAtLeast(0)
            retirement =
                scope.launch {
                    delay(wait)
                    retireDue()
                }
        }
    }

    private fun retirementDeadline(intent: MailIntent): Long? {
        val settled = intent.settledAtMs ?: return null
        return when (intent.state) {
            IntentPhase.Done -> settled + timing.doneRetentionMs
            IntentPhase.Failed -> settled + timing.failedRetentionMs
            else -> null
        }
    }

    private fun retireDue() {
        val now = nowMs()
        val due = synchronized(lock) { intents.filter { retirementDeadline(it)?.let { d -> d <= now } == true } }
        if (due.isNotEmpty()) {
            val done = due.filter { it.state == IntentPhase.Done }
            if (done.isNotEmpty()) observers.forEach { it.intentsWillRetire(done) }
            synchronized(lock) {
                val ids = due.map { it.id }.toSet()
                intents = intents.filterNot { it.id in ids }
                changedLocked()
            }
        }
        scheduleRetirement()
    }

    // State

    private fun replace(
        id: String,
        change: (MailIntent) -> MailIntent,
    ) {
        intents = intents.map { if (it.id == id) change(it) else it }
    }

    /** Every change goes to disk, and never once stopped, when another queue may own the file. */
    private fun changedLocked() {
        if (!stopped) persistence.save(intents)
        publishLocked()
    }

    private fun publishLocked() {
        _state.value = QueueState(intents, sequence, waitingIds, heldUntil)
    }

    private fun touched(intent: MailIntent): List<String> = intent.messageIds

    companion object {
        fun rowState(
            state: QueueState,
            messageId: String,
        ): IntentRowState {
            var result = IntentRowState.None
            for (intent in state.intents) {
                if (messageId !in intent.messageIds) continue
                when (intent.state) {
                    IntentPhase.Failed -> result = IntentRowState.Failed
                    IntentPhase.Pending, IntentPhase.Sending ->
                        if (intent.id in
                            state.waitingIds
                        ) {
                            result = IntentRowState.Waiting
                        }
                    IntentPhase.Done -> Unit
                }
            }
            return result
        }

        /** Whether [state] already carries what [intent] does to its message (null state: gone). [landing] is where a moving action files the message. */
        internal fun shows(
            intent: MailIntent,
            state: MessageState?,
            landing: String?,
        ): Boolean {
            if (state == null) return true
            return when (intent.action) {
                TriageAction.Expunge -> false
                TriageAction.Move, TriageAction.Archive, TriageAction.Trash, TriageAction.Spam, TriageAction.NotSpam ->
                    landing != null && state.folderId == landing
                TriageAction.MarkRead -> state.isSeen == true
                TriageAction.MarkUnread -> state.isSeen == false
                TriageAction.Flag -> state.isFlagged == true
                TriageAction.Unflag -> state.isFlagged == false
            }
        }

        /**
         * Moves back to each message's own origin folder (one intent per folder, expecting each message
         * where the server said it filed it, so one filed elsewhere since stays there), or read and star
         * state flipped back. Expunge has nothing to reverse.
         */
        internal fun reversal(intent: MailIntent): List<IntentRequest> {
            intent.action.inverse?.let { inverse ->
                return listOf(
                    IntentRequest(intent.accountId, inverse, intent.messageIds, delivery = intent.delivery),
                )
            }
            if (intent.action == TriageAction.Expunge) return emptyList()
            val moved: List<Pair<String, String>> =
                if (intent.movedSources.isEmpty()) {
                    intent.messageIds.mapNotNull { id -> intent.originFolderIds[id]?.let { id to it } }
                } else {
                    intent.movedSources.map { it.id to it.folderId }
                }
            val byFolder = LinkedHashMap<String, MutableList<String>>()
            moved.forEach { (message, folder) -> byFolder.getOrPut(folder) { mutableListOf() } += message }
            return byFolder.keys.sorted().map { folder ->
                val ids = byFolder.getValue(folder)
                IntentRequest(
                    accountId = intent.accountId,
                    action = TriageAction.Move,
                    messageIds = ids,
                    targetFolderId = folder,
                    delivery = if (ids.size == 1) Delivery.Message else Delivery.Bulk(expandThreads = false),
                    originFolderIds = intent.filedFolderIds.filterKeys { it in ids },
                    snapshots = intent.snapshots.filter { it.id in ids },
                )
            }
        }
    }
}

/** Where intents are kept between runs. */
interface IntentPersistence {
    fun load(): List<MailIntent>

    fun save(intents: List<MailIntent>)
}

class InMemoryIntentPersistence(
    var stored: List<MailIntent> = emptyList(),
) : IntentPersistence {
    override fun load() = stored

    override fun save(intents: List<MailIntent>) {
        stored = intents
    }
}
