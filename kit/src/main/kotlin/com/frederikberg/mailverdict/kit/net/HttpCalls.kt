package com.frederikberg.mailverdict.kit.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A fully read answer: status, headers and the whole body. */
class RawResponse(
    val code: Int,
    val headers: Headers,
    val body: ByteArray,
) {
    val contentType: String? get() = headers["Content-Type"]
}

/**
 * Runs the call and reads the whole body on OkHttp's own thread, so the resuming coroutine is free
 * to be on the main dispatcher. Cancelling the coroutine cancels the call.
 */
internal suspend fun Call.awaitRaw(): RawResponse =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    val raw =
                        try {
                            response.use { RawResponse(it.code, it.headers, it.body.bytes()) }
                        } catch (e: IOException) {
                            continuation.resumeWithException(e)
                            return
                        }
                    continuation.resume(raw)
                }
            },
        )
    }

/** What the person is told when a request never got an answer. */
internal fun IOException.toApiError(): ApiError =
    ApiError.Transport(
        when (this) {
            is SocketTimeoutException -> "The server took too long to answer."
            is UnknownHostException, is ConnectException -> "Could not reach the server."
            else -> message?.takeIf { it.isNotBlank() } ?: "The connection failed."
        },
        this,
    )

/** A failure worth asking again for: the server was momentarily unreachable, not refusing. */
internal fun IOException.isTransient(): Boolean = this is SocketException
