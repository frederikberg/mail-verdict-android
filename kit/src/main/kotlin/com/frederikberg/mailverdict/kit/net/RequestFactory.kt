package com.frederikberg.mailverdict.kit.net

import com.frederikberg.mailverdict.kit.auth.AuthMode
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Builds every request the app sends. One place owns the base URL, the `/api` prefix and the
 * bearer header, so the REST client and the event stream apply them the same way.
 */
class RequestFactory(
    baseUrl: String,
    /** Read at send time rather than captured, so a credential entered or renewed applies to the next request. */
    private val authProvider: () -> AuthMode,
) {
    sealed class ConfigurationError(
        message: String,
    ) : IllegalArgumentException(message) {
        class EmptyBaseUrl : ConfigurationError("The server address is empty.")

        class UnsupportedScheme(
            val scheme: String?,
        ) : ConfigurationError("The server address must start with https:// or http://.")

        class MalformedBaseUrl : ConfigurationError("The server address is not a valid URL.")
    }

    private val base: HttpUrl = parseBase(baseUrl)

    /** [path] starts with `/` and is the route as documented, without the `/api` prefix; it may carry percent-escapes. */
    fun build(
        path: String,
        method: String = "GET",
        query: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        contentType: String? = null,
        accept: String = JSON,
    ): Request {
        val url =
            base
                .newBuilder()
                .apply {
                    // The base may itself sit under a path prefix (a reverse proxy mounting the app).
                    addEncodedPathSegments("api" + path)
                    query.forEach { (name, value) -> addQueryParameter(name, value) }
                }.build()
        val requestBody: RequestBody? =
            when {
                body != null -> body.toRequestBody(contentType?.toMediaType())
                method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody()
                else -> null
            }
        return Request
            .Builder()
            .url(url)
            .method(method, requestBody)
            .header("Accept", accept)
            .apply {
                when (val mode = authProvider()) {
                    AuthMode.None -> Unit
                    is AuthMode.Bearer -> if (mode.token.isNotEmpty()) header("Authorization", "Bearer ${mode.token}")
                    is AuthMode.Oidc ->
                        if (mode.tokens.accessToken.isNotEmpty()) {
                            header(
                                "Authorization",
                                "Bearer ${mode.tokens.accessToken}",
                            )
                        }
                }
            }.build()
    }

    companion object {
        const val JSON = "application/json"

        /** The token of an `Authorization: Bearer …` header, if the request carries one. */
        fun bearerOf(
            request: Request,
        ): String? = request.header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")

        private fun parseBase(raw: String): HttpUrl {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw ConfigurationError.EmptyBaseUrl()
            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase().ifEmpty { null }
            if (scheme != "https" && scheme != "http") throw ConfigurationError.UnsupportedScheme(scheme)
            val url = trimmed.toHttpUrlOrNull() ?: throw ConfigurationError.MalformedBaseUrl()
            // Normalise a trailing slash away so segments append cleanly.
            return if (url.pathSegments.lastOrNull() ==
                ""
            ) {
                url.newBuilder().removePathSegment(url.pathSize - 1).build()
            } else {
                url
            }
        }
    }
}
