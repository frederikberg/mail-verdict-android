package com.frederikberg.mailverdict.kit.net

import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.kit.auth.CredentialRefreshing
import com.frederikberg.mailverdict.kit.auth.RefreshOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A body that is not JSON: an attachment, a raw message, a photo. */
class RawBody(
    val data: ByteArray,
    val contentType: String?,
    val suggestedFilename: String?,
)

/**
 * The app's one REST client. Every route goes through [send], [sendNoContent] or [sendRaw], so
 * status handling, decoding, token renewal and the sign-out hook live in exactly one place. Area
 * extensions (`kit/api/`) only name routes.
 *
 * With a [credentialRefresher], a request the server refuses for its credential (a 401, or the
 * proxy's bare 403) renews the token and is sent once more; [onAuthenticationFailure] fires only when
 * that did not help, or when the user was disabled. It is a callback here and not something callers
 * check because the alternative fails quietly: a first-load path is full of calls whose errors are
 * discarded, and an expired token then reads as an empty app with no way back.
 */
class ApiClient(
    private val requests: RequestFactory,
    httpClient: OkHttpClient,
    private val json: Json = ContractJson,
    private val onAuthenticationFailure: ((ApiError) -> Unit)? = null,
    private val readRetry: ReadRetryPolicy = ReadRetryPolicy(),
    private val credentialRefresher: CredentialRefreshing? = null,
) {
    /** Redirects are never followed: a login proxy's 302 must reach [checkStatus], not be decoded as JSON. */
    private val http: OkHttpClient =
        httpClient
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    suspend fun <T> send(
        strategy: DeserializationStrategy<T>,
        path: String,
        method: String = "GET",
        query: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        timeoutMs: Long? = null,
        /** Statuses whose body is the answer rather than an error, such as the readiness probe's 503. */
        answerStatuses: Set<Int> = emptySet(),
    ): T {
        val response = exchange(path, method, query, body, if (body != null) JSON_CONTENT else null, timeoutMs)
        if (response.code !in answerStatuses) checkStatus(response)
        return try {
            json.decodeFromString(strategy, response.body.decodeToString())
        } catch (e: SerializationException) {
            throw ApiError.Decoding(e.message.orEmpty(), e)
        } catch (e: IllegalArgumentException) {
            throw ApiError.Decoding(e.message.orEmpty(), e)
        }
    }

    /** For a `204 No Content` route, or any route whose body the caller does not need. */
    suspend fun sendNoContent(
        path: String,
        method: String,
        query: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
    ) {
        checkStatus(exchange(path, method, query, body, if (body != null) JSON_CONTENT else null, null))
    }

    /** For a route that answers bytes rather than JSON. */
    suspend fun sendRaw(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): RawBody {
        val response = exchange(path, "GET", query, null, null, null, accept = "*/*")
        checkStatus(response, jsonExpected = false)
        return RawBody(
            response.body,
            response.contentType,
            filenameFromContentDisposition(response.headers["Content-Disposition"]),
        )
    }

    /** Encodes a request body with the app's one JSON configuration. */
    fun <T> encode(
        serializer: kotlinx.serialization.SerializationStrategy<T>,
        value: T,
    ): ByteArray = json.encodeToString(serializer, value).toByteArray()

    private suspend fun exchange(
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        body: ByteArray?,
        contentType: String?,
        timeoutMs: Long?,
        accept: String = RequestFactory.JSON,
    ): RawResponse {
        suspend fun once() = transmit(path, method, query, body, contentType, timeoutMs, accept)
        val refresher = credentialRefresher ?: return once().response
        refresher.refreshIfNeeded()
        val first = once()
        val refused = first.response.code.let { it == 401 || it == 403 }
        if (!refused ||
            !ApiError.from(first.response.code, first.response.body).isAuthenticationFailure
        ) {
            return first.response
        }

        // The refused request never ran (a 401, or the proxy's 403 in front of the app), so sending it
        // again after a renewal cannot repeat a write.
        return when (refresher.refresh(afterRejectionOf = first.bearer)) {
            RefreshOutcome.Refreshed -> once().response
            RefreshOutcome.Rejected -> first.response
            // Not reaching the provider says nothing about the credential: surfacing the 401 would
            // sign the person out over a bad connection.
            RefreshOutcome.Unavailable -> throw ApiError.Transport(
                "The sign-in could not be renewed. Check the connection and try again.",
            )
        }
    }

    /** One answered request with the bearer token it went out under: the token a renewal must treat as rejected. */
    private class Transmitted(
        val response: RawResponse,
        val bearer: String?,
    )

    private suspend fun transmit(
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        body: ByteArray?,
        contentType: String?,
        timeoutMs: Long?,
        accept: String,
    ): Transmitted {
        val request: Request =
            try {
                requests.build(path, method, query, body, contentType, accept)
            } catch (e: RequestFactory.ConfigurationError) {
                throw ApiError.Transport(e.message.orEmpty(), e)
            }
        val bearer = RequestFactory.bearerOf(request)
        val isRead = method == "GET"
        val client =
            when {
                timeoutMs != null -> http.withCallTimeout(timeoutMs)
                isRead -> http.withCallTimeout(readRetry.readTimeoutMs)
                else -> http
            }
        val delays = if (isRead) readRetry.delaysMs.toMutableList() else mutableListOf()
        while (true) {
            try {
                val response = client.newCall(request).awaitRaw()
                if (delays.isNotEmpty() && ReadRetryPolicy.isTransient(response.code)) {
                    delay(delays.removeAt(0))
                    continue
                }
                return Transmitted(response, bearer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (delays.isNotEmpty() && e.isTransient()) {
                    delay(delays.removeAt(0))
                    continue
                }
                throw e.toApiError()
            }
        }
    }

    private fun checkStatus(
        response: RawResponse,
        jsonExpected: Boolean = true,
    ) {
        // Redirects are not followed, so a 3xx here is the redirect itself: a single sign-on's login page.
        if (response.code in 300..399) throw ApiError.ProxyRequiresBrowserLogin()
        if (response.code !in 200..299) {
            val error = ApiError.from(response.code, response.body)
            if (error.endsSession) onAuthenticationFailure?.invoke(error)
            throw error
        }
        // A 200 whose body is HTML is the same login-proxy case without a redirect.
        if (jsonExpected && response.contentType.orEmpty().contains("text/html", ignoreCase = true)) {
            throw ApiError.ProxyRequiresBrowserLogin()
        }
    }

    private fun OkHttpClient.withCallTimeout(ms: Long): OkHttpClient =
        newBuilder()
            .callTimeout(
                ms,
                TimeUnit.MILLISECONDS,
            ).build()

    companion object {
        private const val JSON_CONTENT = "application/json"

        /**
         * The filename a `Content-Disposition` header suggests: `filename=` or the RFC 5987
         * `filename*=UTF-8''…` form the server always sends alongside it, which wins.
         */
        fun filenameFromContentDisposition(header: String?): String? {
            if (header == null) return null
            val parts = header.split(";").map { it.trim() }
            parts.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }?.let { part ->
                val value = part.substringAfter("=")
                val encoded = if ("''" in value) value.substringAfter("''") else value
                return runCatching {
                    java.net.URLDecoder.decode(
                        encoded.replace("+", "%2B"),
                        "UTF-8",
                    )
                }.getOrDefault(encoded)
            }
            parts.firstOrNull { it.startsWith("filename=", ignoreCase = true) }?.let { part ->
                return part.substringAfter("=").removeSurrounding("\"")
            }
            return null
        }
    }
}
