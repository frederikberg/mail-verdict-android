package com.frederikberg.mailverdict.kit.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One open event-stream response. */
interface SseConnection : Closeable {
    val status: Int

    /** For a non-2xx answer, what the body said (bounded); empty for a stream. */
    val errorBody: ByteArray

    /** The next bytes the server sent, or null once it closed the stream. Cancelling the caller aborts the read. */
    suspend fun readChunk(): ByteArray?
}

/** Opens the event stream. An interface so the client's reconnect rules are tested against a scripted stream, not a socket. */
fun interface SseTransport {
    /** Throws [IOException] when the server cannot be reached. */
    suspend fun open(request: Request): SseConnection
}

/** The OkHttp transport: no read timeout, because the client's watchdog decides when a quiet stream is dead. */
class OkHttpSseTransport(
    httpClient: OkHttpClient,
) : SseTransport {
    private val http =
        httpClient
            .newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    override suspend fun open(request: Request): SseConnection {
        val call = http.newCall(request)
        val response = abortOnCancel(call) { call.execute() }
        if (response.isSuccessful) return OkHttpSseConnection(call, response, ByteArray(0))
        val body = response.use { runCatching { it.peekBody(MAX_ERROR_BODY).bytes() }.getOrDefault(ByteArray(0)) }
        return object : SseConnection {
            override val status: Int = response.code
            override val errorBody: ByteArray = body

            override suspend fun readChunk(): ByteArray? = null

            override fun close() = Unit
        }
    }

    private class OkHttpSseConnection(
        private val call: Call,
        private val response: Response,
        override val errorBody: ByteArray,
    ) : SseConnection {
        override val status: Int = response.code
        private val buffer = ByteArray(READ_BUFFER)

        override suspend fun readChunk(): ByteArray? =
            abortOnCancel(call) {
                val read = response.body.source().read(buffer)
                if (read < 0) null else buffer.copyOf(read)
            }

        override fun close() {
            call.cancel()
            response.close()
        }
    }

    private companion object {
        const val MAX_ERROR_BODY = 4096L
        const val READ_BUFFER = 8192
    }
}

/**
 * Runs a blocking call off the caller's thread and cancels the OkHttp [call] when the caller is
 * cancelled, which is the only thing that interrupts a blocked socket read.
 */
private suspend fun <T> abortOnCancel(
    call: Call,
    block: () -> T,
): T =
    coroutineScope {
        val work = async(Dispatchers.IO) { block() }
        try {
            work.await()
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }
    }
