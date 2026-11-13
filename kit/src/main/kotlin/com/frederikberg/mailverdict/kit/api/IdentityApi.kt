package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.AuthConfigResponse
import com.frederikberg.mailverdict.contract.HealthResponse
import com.frederikberg.mailverdict.contract.MeResponse
import com.frederikberg.mailverdict.kit.net.ApiClient

suspend fun ApiClient.getMe(): MeResponse = send(MeResponse.serializer(), "/me")

/** Needs no token: what a native app starts its sign-in from. */
suspend fun ApiClient.getAuthConfig(): AuthConfigResponse = send(AuthConfigResponse.serializer(), "/auth/config")

/** Readiness. The server answers the same JSON whether it is ready (200) or not (503), so a 503 here decodes rather than throws. */
suspend fun ApiClient.getHealth(): HealthResponse =
    send(
        HealthResponse.serializer(),
        "/health",
        answerStatuses = setOf(503),
    )
