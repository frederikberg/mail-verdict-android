package com.frederikberg.mailverdict.kit.net

import kotlinx.coroutines.CancellationException

/** How a write a person asked for ended: done, or refused with words for them. */
sealed interface WriteOutcome {
    data object Done : WriteOutcome

    /** [message] is what the server or the network said; [status] is the HTTP status when the server answered. */
    data class Failed(
        val message: String,
        val status: Int? = null,
    ) : WriteOutcome {
        /** The thing written changed on the server since it was read. */
        val isConflict: Boolean get() = status == 409
    }

    val isDone: Boolean get() = this is Done
}

/** Runs [block] and turns an [ApiError] into [WriteOutcome.Failed]; cancellation is never swallowed. */
suspend inline fun writeOutcome(block: () -> Unit): WriteOutcome =
    try {
        block()
        WriteOutcome.Done
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiError) {
        WriteOutcome.Failed(e.userMessage, e.statusCode)
    }
