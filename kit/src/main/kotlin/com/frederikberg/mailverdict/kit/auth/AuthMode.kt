package com.frederikberg.mailverdict.kit.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How this install authenticates to its server. The request factory reads the current mode at send
 * time, so a credential entered or renewed takes effect on the next request without rebuilding the
 * client.
 */
@Serializable
sealed class AuthMode {
    /** No credential: what a request before sign-in goes out with. Never stored. */
    @Serializable
    @SerialName("none")
    data object None : AuthMode()

    /** An OpenID Connect sign-in: the access token goes out as a bearer, the refresh token keeps it alive. */
    @Serializable
    @SerialName("oidc")
    data class Oidc(
        val tokens: OidcTokens,
    ) : AuthMode()
}

/**
 * The token set a sign-in leaves behind. One value, replaced whole on every refresh, so a rotated
 * refresh token can never be stored apart from the access token it arrived with.
 */
@Serializable
data class OidcTokens(
    @SerialName("access_token") val accessToken: String,
    /** Null when the provider issued none (no `offline_access` grant): the session then ends with the access token. */
    @SerialName("refresh_token") val refreshToken: String? = null,
    /** Epoch milliseconds; null when the provider stated no lifetime, so the token is used until the server refuses it. */
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
    val issuer: String,
    @SerialName("client_id") val clientId: String,
) {
    /** Whether the access token is expired or about to be, with [skewMs] of margin for travel time and clock drift. */
    fun isExpired(
        nowMs: Long,
        skewMs: Long = DEFAULT_SKEW_MS,
    ): Boolean = expiresAtMs?.let { it - nowMs <= skewMs } ?: false

    /** The token set with its secrets masked, for logs. */
    override fun toString(): String = "OidcTokens(issuer=$issuer, clientId=$clientId, refreshToken=${refreshToken?.let {
        "…"
    }})"

    companion object {
        const val DEFAULT_SKEW_MS: Long = 60_000
    }
}
