package com.frederikberg.mailverdict.kit.auth

import com.frederikberg.mailverdict.contract.AuthConfigResponse
import com.frederikberg.mailverdict.kit.net.RawResponse
import com.frederikberg.mailverdict.kit.net.awaitRaw
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

sealed class OidcError(
    message: String,
) : Exception(message) {
    /** The provider's discovery document could not be read or does not describe this issuer. */
    class DiscoveryFailed(
        message: String,
    ) : OidcError(message)

    /** The server offers no native sign-in (`client_id` is null in `GET /api/auth/config`). */
    class SignInUnavailable : OidcError("This server does not offer sign-in from the app.")

    /** The redirect back to the app carried an error, no code, or a `state` that is not this attempt's. */
    class CallbackRejected(
        message: String,
    ) : OidcError(message)

    /** The provider answered a token request with a definitive no: an expired, revoked or used code or refresh token. */
    class Rejected(
        message: String,
    ) : OidcError(message)

    /** The provider could not be asked, or did not answer sensibly. Says nothing about the credential. */
    class Unavailable(
        message: String,
    ) : OidcError(message)

    val isRejection: Boolean get() = this is Rejected
}

/** The parts of a provider's `/.well-known/openid-configuration` the sign-in uses. */
@Serializable
data class OidcProviderMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    /** Absent when the provider cannot revoke tokens; signing out then only forgets them on the phone. */
    @SerialName("revocation_endpoint") val revocationEndpoint: String? = null,
)

/**
 * Talks OpenID Connect to the provider named by `GET /api/auth/config`: discovery, the authorization
 * URL the browser opens, the code exchange and the refresh grant. It holds no state; [TokenRefresher]
 * owns when a refresh happens and where the result is kept.
 */
class OidcClient(
    httpClient: OkHttpClient = OkHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val http = httpClient.newBuilder().followRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(issuer: String): OidcProviderMetadata {
        val base = issuer.trimEnd('/')
        val issuerUrl =
            base.toHttpUrlOrNull() ?: throw OidcError.DiscoveryFailed("The sign-in address is not a valid URL.")
        val response =
            send(
                Request
                    .Builder()
                    .url(
                        "$base/.well-known/openid-configuration",
                    ).header("Accept", "application/json")
                    .build(),
            )
        if (response.code !in 200..299) throw OidcError.Unavailable("The sign-in provider did not answer.")
        val metadata =
            runCatching { json.decodeFromString<OidcProviderMetadata>(response.body.decodeToString()) }
                .getOrElse {
                    throw OidcError.DiscoveryFailed(
                        "The sign-in provider's configuration could not be read.",
                    )
                }
        // A discovery document naming another issuer, or endpoints on a weaker scheme than the issuer
        // itself, would send the credential somewhere the server never vouched for.
        val authorization = metadata.authorizationEndpoint.toHttpUrlOrNull()
        val token = metadata.tokenEndpoint.toHttpUrlOrNull()
        val revocation = metadata.revocationEndpoint?.let { it.toHttpUrlOrNull() ?: throw mismatch() }
        if (metadata.issuer.trimEnd('/') != base ||
            authorization?.scheme != issuerUrl.scheme ||
            token?.scheme != issuerUrl.scheme ||
            (revocation != null && revocation.scheme != issuerUrl.scheme)
        ) {
            throw mismatch()
        }
        return metadata
    }

    private fun mismatch() =
        OidcError.DiscoveryFailed(
            "The sign-in provider's configuration does not match its address.",
        )

    /** The URL the browser opens. [state] and [pkce] are the caller's, kept for [parseCallback] and [exchangeCode]. */
    fun authorizationUrl(
        config: AuthConfigResponse,
        metadata: OidcProviderMetadata,
        pkce: Pkce.Codes,
        state: String,
    ): String {
        val clientId = config.clientId?.takeIf { it.isNotEmpty() } ?: throw OidcError.SignInUnavailable()
        val endpoint: HttpUrl =
            metadata.authorizationEndpoint.toHttpUrlOrNull()
                ?: throw OidcError.DiscoveryFailed("The sign-in provider's configuration could not be read.")
        return endpoint
            .newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", config.redirectUri)
            .addQueryParameter("scope", config.scopes)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    /** The authorization code in the redirect that ended the browser leg, once it is proven to belong to this attempt. */
    fun parseCallback(
        callback: String,
        expectedState: String,
    ): String {
        val query = runCatching { URI(callback).rawQuery }.getOrNull().orEmpty()
        val items =
            query
                .split("&")
                .filter { it.isNotEmpty() }
                .associate { part ->
                    val name = part.substringBefore("=")
                    // A literal `+` in a query is a space under form rules; the provider escapes a real plus as %2B.
                    name to URLDecoder.decode(part.substringAfter("=", ""), Charsets.UTF_8)
                }
        items["error"]?.let { throw OidcError.CallbackRejected(items["error_description"] ?: it) }
        if (items["state"] !=
            expectedState
        ) {
            throw OidcError.CallbackRejected("The sign-in answer did not belong to this attempt.")
        }
        return items["code"]?.takeIf { it.isNotEmpty() }
            ?: throw OidcError.CallbackRejected("The sign-in answer carried no code.")
    }

    suspend fun exchangeCode(
        code: String,
        pkce: Pkce.Codes,
        config: AuthConfigResponse,
        metadata: OidcProviderMetadata,
    ): OidcTokens {
        val clientId = config.clientId?.takeIf { it.isNotEmpty() } ?: throw OidcError.SignInUnavailable()
        val response =
            tokenRequest(
                metadata.tokenEndpoint,
                listOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to config.redirectUri,
                    "client_id" to clientId,
                    "code_verifier" to pkce.verifier,
                ),
            )
        return tokensFrom(response, config.issuer, clientId, previousRefreshToken = null)
    }

    /** The refresh grant. A provider that rotates refresh tokens returns a new one, which replaces the old; one that does not leaves the old in force. */
    suspend fun refresh(
        tokens: OidcTokens,
        metadata: OidcProviderMetadata,
    ): OidcTokens {
        val refreshToken = tokens.refreshToken ?: throw OidcError.Rejected("no_refresh_token")
        val response =
            tokenRequest(
                metadata.tokenEndpoint,
                listOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to tokens.clientId,
                ),
            )
        return tokensFrom(response, tokens.issuer, tokens.clientId, previousRefreshToken = refreshToken)
    }

    /**
     * Asks the provider to end this sign-in (RFC 7009): its refresh token stops working, and the access
     * tokens issued from it with it. Does nothing when the provider names no revocation endpoint. A
     * provider answers 200 whether or not it knew the token, so any other answer is an outage.
     */
    suspend fun revoke(
        tokens: OidcTokens,
        metadata: OidcProviderMetadata,
    ) {
        val endpoint = metadata.revocationEndpoint ?: return
        val (token, hint) =
            tokens.refreshToken?.let { it to "refresh_token" } ?: (tokens.accessToken to "access_token")
        val request =
            Request
                .Builder()
                .url(endpoint)
                .post(
                    formEncoded(
                        listOf("token" to token, "token_type_hint" to hint, "client_id" to tokens.clientId),
                    ).toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                ).build()
        val response = send(request)
        if (response.code !in
            200..299
        ) {
            throw OidcError.Unavailable("The sign-in provider answered HTTP ${response.code}.")
        }
    }

    @Serializable
    private class TokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Double? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null,
    )

    private suspend fun tokenRequest(
        endpoint: String,
        form: List<Pair<String, String>>,
    ): TokenResponse {
        val request =
            Request
                .Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .post(formEncoded(form).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
        val response = send(request)
        val decoded = runCatching { json.decodeFromString<TokenResponse>(response.body.decodeToString()) }.getOrNull()
        if (response.code in 200..299) {
            return decoded ?: throw OidcError.Unavailable("The sign-in provider's answer could not be read.")
        }
        // 400, 401 and 403 are the provider refusing the grant. 408, 429 and 5xx are it being busy.
        if (response.code in REFUSAL_STATUSES) {
            throw OidcError.Rejected(decoded?.errorDescription ?: decoded?.error ?: "HTTP ${response.code}")
        }
        throw OidcError.Unavailable("The sign-in provider answered HTTP ${response.code}.")
    }

    private fun tokensFrom(
        response: TokenResponse,
        issuer: String,
        clientId: String,
        previousRefreshToken: String?,
    ): OidcTokens {
        val access =
            response.accessToken?.takeIf { it.isNotEmpty() }
                ?: throw OidcError.Unavailable("The sign-in provider's answer carried no token.")
        return OidcTokens(
            accessToken = access,
            refreshToken = response.refreshToken ?: previousRefreshToken,
            expiresAtMs = response.expiresIn?.let { nowMs() + (it * 1000).toLong() },
            issuer = issuer,
            clientId = clientId,
        )
    }

    private suspend fun send(request: Request): RawResponse =
        try {
            http.newCall(request).awaitRaw()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw OidcError.Unavailable(
                e.message?.takeIf { it.isNotBlank() } ?: "The sign-in provider could not be reached.",
            )
        }

    companion object {
        private val REFUSAL_STATUSES = setOf(400, 401, 403)

        /**
         * `application/x-www-form-urlencoded` with only RFC 3986 unreserved characters left bare, so a
         * `+` or `=` inside a token cannot be misread as a separator, and a space is `%20`.
         */
        fun formEncoded(fields: List<Pair<String, String>>): String =
            fields.joinToString("&") { (name, value) -> "${escape(name)}=${escape(value)}" }

        private fun escape(text: String): String =
            URLEncoder
                .encode(
                    text,
                    Charsets.UTF_8,
                ).replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~")
    }
}
