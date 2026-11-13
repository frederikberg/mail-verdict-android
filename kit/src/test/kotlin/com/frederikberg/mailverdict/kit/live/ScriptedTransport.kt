package com.frederikberg.mailverdict.kit.live

import kotlinx.coroutines.channels.Channel
import okhttp3.Request
import java.io.IOException

/**
 * An event-stream transport driven by the test: each `open` takes the next prepared connection, and
 * a connection delivers exactly the chunks the test pushes into it. Nothing here touches a socket, so
 * a test can run reconnect rules in virtual time.
 */
class ScriptedTransport : SseTransport {
    class Connection(
        override val status: Int = 200,
        override val errorBody: ByteArray = ByteArray(0),
    ) : SseConnection {
        private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

        @Volatile var closed = false
            private set

        /** Bytes the server sends. */
        fun send(text: String) {
            chunks.trySend(text.toByteArray())
        }

        /** The server closes the stream. */
        fun end() {
            chunks.close()
        }

        override suspend fun readChunk(): ByteArray? = chunks.receiveCatching().getOrNull()

        override fun close() {
            closed = true
        }
    }

    private val prepared = ArrayDeque<Connection>()
    private var fallback: (() -> Connection)? = null
    private val _requests = mutableListOf<Request>()
    private val _connections = mutableListOf<Connection>()

    /** Every request the client opened, oldest first. */
    val requests: List<Request> get() = synchronized(this) { _requests.toList() }
    val connections: List<Connection> get() = synchronized(this) { _connections.toList() }

    /** Streams open right now (opened and not yet closed by the client or ended by the server). */
    val live: Int get() = connections.count { !it.closed }

    /** The most streams that were ever open at the same instant. */
    @Volatile var peak = 0
        private set

    /** Throw an [IOException] from the next `open` calls instead of answering. */
    @Volatile var unreachable = false

    /** Queue a connection for a later `open`. */
    fun next(
        connection: Connection = Connection(),
    ): Connection = connection.also { synchronized(this) { prepared.addLast(it) } }

    /** Every `open` with nothing queued gets a fresh connection from [make]. */
    fun otherwise(make: () -> Connection) {
        fallback = make
    }

    override suspend fun open(request: Request): SseConnection {
        if (unreachable) throw IOException("unreachable")
        synchronized(this) {
            val connection =
                prepared.removeFirstOrNull() ?: fallback?.invoke()
                    ?: error("the client opened a stream nobody prepared")
            _requests += request
            _connections += connection
            peak = maxOf(peak, _connections.count { !it.closed })
            return connection
        }
    }
}
