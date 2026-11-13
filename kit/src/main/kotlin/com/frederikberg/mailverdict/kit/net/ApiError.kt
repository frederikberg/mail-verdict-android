package com.frederikberg.mailverdict.kit.net

import com.frederikberg.mailverdict.contract.ContractJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What went wrong with a request, classified by what the caller must do about it.
 *
 * The server's own refusals are JSON with a `detail`. Anything in front of it (the login proxy)
 * answers without one, which is what separates a refused credential from a refused request: an
 * invalid bearer gets a bare non-JSON 403 from the proxy, a missing one a 401, while the app's own
 * 403s always carry a `detail`.
 */
sealed class ApiError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The server answered with a JSON body carrying `detail`. */
    class Detail(
        val detail: String,
        override val statusCode: Int,
    ) : ApiError(detail)

    /** The server answered with a body that is not JSON, or has no `detail`; the status line is all there is. */
    class Http(
        override val statusCode: Int,
        val reason: String,
    ) : ApiError("HTTP $statusCode: $reason")

    /** The request never got an answer: no network, a timeout, a dropped connection. */
    class Transport(
        message: String,
        cause: Throwable? = null,
    ) : ApiError(message, cause)

    /** The answer arrived but is not the shape the app expected. */
    class Decoding(
        val technicalDetail: String,
        cause: Throwable? = null,
    ) : ApiError("The server's response could not be read.", cause)

    /**
     * A 3xx, or an HTML body, on an API call: a login page came back instead of JSON, most commonly a
     * cookie-only single sign-on redirecting a request it does not recognise.
     */
    class ProxyRequiresBrowserLogin :
        ApiError(
            "This server's login proxy wants a browser sign-in and does not accept an access token " +
                "for API requests.",
        )

    /** The HTTP status the server answered with, when it answered at all. */
    open val statusCode: Int? get() = null

    /**
     * The server or the proxy in front of it refused the credential rather than the request: a 401, or
     * a 403 that is not the app's own. Asked of the error so no call site has to remember the rule.
     */
    val isAuthenticationFailure: Boolean
        get() =
            when (this) {
                is Detail -> statusCode == 401
                is Http -> statusCode == 401 || statusCode == 403
                else -> false
            }

    /** The app itself refused this one request (a JSON 403): the credential is fine, the request is not theirs to make. */
    val isForbidden: Boolean get() = this is Detail && statusCode == 403

    /** The app refused because the signed-in user was disabled: nothing the user does makes the next request succeed. */
    val isDisabled: Boolean get() = this is Detail && statusCode == 403 && detail == DISABLED_DETAIL

    /** Whether the session is over: a rejected credential, or a disabled user. */
    val endsSession: Boolean get() = isAuthenticationFailure || isDisabled

    /** What the person should see; never empty. */
    val userMessage: String get() = message.orEmpty().ifBlank { "Something went wrong." }

    companion object {
        /** The `detail` the server's auth layer answers a disabled user with. */
        const val DISABLED_DETAIL = "user_disabled"

        /** Classifies a non-2xx answer the transport already has in hand. */
        fun from(
            statusCode: Int,
            body: ByteArray,
        ): ApiError {
            val detail = detailOf(body)
            return if (detail != null) Detail(detail, statusCode) else Http(statusCode, reasonPhrase(statusCode))
        }

        private fun detailOf(body: ByteArray): String? =
            runCatching { ContractJson.parseToJsonElement(body.decodeToString()) }
                .getOrNull()
                .let { it as? JsonObject }
                ?.get("detail")
                .let { it as? JsonPrimitive }
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.takeIf { it.isNotEmpty() }

        private fun reasonPhrase(statusCode: Int): String =
            when (statusCode) {
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                409 -> "Conflict"
                413 -> "Payload Too Large"
                422 -> "Unprocessable Entity"
                429 -> "Too Many Requests"
                500 -> "Internal Server Error"
                501 -> "Not Implemented"
                502 -> "Bad Gateway"
                503 -> "Service Unavailable"
                504 -> "Gateway Timeout"
                else -> "Error"
            }
    }
}
