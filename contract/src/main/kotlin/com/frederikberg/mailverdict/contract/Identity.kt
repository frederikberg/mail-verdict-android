package com.frederikberg.mailverdict.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /me`: who the token signs in as. */
@Serializable
data class MeResponse(
    @SerialName("user_id") val userId: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String,
    val role: UserRole,
    val disabled: Boolean,
) {
    val isAdmin: Boolean get() = role == UserRole.Admin
}

/**
 * `GET /auth/config`, answered without a token: what a native app needs to start its sign-in.
 * `clientId` is null when the server offers no native sign-in.
 */
@Serializable
data class AuthConfigResponse(
    val issuer: String,
    @SerialName("client_id") val clientId: String? = null,
    val scopes: String,
    @SerialName("redirect_uri") val redirectUri: String,
)

/**
 * `GET /health`. The route has no schema of its own in the published contract, so this model is
 * checked only by the tests that decode a captured body. `status` is `ready` or `not_ready`; the two
 * diagnostic fields are informal strings, never a closed set.
 */
@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("postimap_contract") val postimapContract: String,
    val database: String,
    val version: String? = null,
) {
    val isReady: Boolean get() = status == "ready"
}
