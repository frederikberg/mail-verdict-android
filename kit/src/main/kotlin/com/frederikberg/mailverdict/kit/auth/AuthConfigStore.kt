package com.frederikberg.mailverdict.kit.auth

import com.frederikberg.mailverdict.contract.AuthConfigResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sign-in parameters of a server (`GET /api/auth/config`, answered without a token), fetched once
 * per server address and kept for the life of the process. A failed fetch is never cached, so
 * correcting a mistyped address and trying again asks again.
 */
class AuthConfigStore(
    private val fetch: suspend (serverUrl: String) -> AuthConfigResponse,
) {
    private val mutex = Mutex()
    private val cache = HashMap<String, AuthConfigResponse>()

    suspend fun config(serverUrl: String): AuthConfigResponse =
        mutex.withLock {
            val key = serverUrl.trim().trimEnd('/').lowercase()
            cache[key] ?: fetch(serverUrl).also { cache[key] = it }
        }
}
