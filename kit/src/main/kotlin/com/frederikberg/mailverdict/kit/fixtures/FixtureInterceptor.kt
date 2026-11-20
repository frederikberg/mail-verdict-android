package com.frederikberg.mailverdict.kit.fixtures

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Answers requests from canned JSON instead of the network, so a screen can be rendered and a
 * store exercised without a server. The same instance serves unit tests and the app's fixture mode.
 *
 * [routes] maps `"METHOD /path"` to the response body. A key may also name one query parameter,
 * `"METHOD /path?name=value"`, which wins over the bare path when the request carries it. An unknown
 * route answers 404 with a JSON body, which is what an unmocked request to the real server looks like.
 */
class FixtureInterceptor(
    private val routes: Map<String, String>,
) : Interceptor {
    /** One request as it went out: its `"METHOD /path"`, its content type and its body. */
    class Recorded(
        val key: String,
        val contentType: String?,
        val body: ByteArray,
    )

    private val seen = mutableListOf<Recorded>()

    /** Every `"METHOD /path"` this interceptor was asked for, oldest first. */
    val requests: List<String>
        get() = synchronized(seen) { seen.map { it.key } }

    /** Every request with its body, oldest first. */
    val recorded: List<Recorded>
        get() = synchronized(seen) { seen.toList() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = "${request.method} ${request.url.encodedPath}"
        val body = request.body?.let { Buffer().also(it::writeTo).readByteArray() } ?: ByteArray(0)
        synchronized(seen) { seen += Recorded(key, request.body?.contentType()?.toString(), body) }

        val answer =
            request.url.queryParameterNames.firstNotNullOfOrNull { name ->
                routes["$key?$name=${request.url.queryParameter(name)}"]
            } ?: routes[key]
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (answer == null) 404 else 200)
            .message(if (answer == null) "Not Found" else "OK")
            .body((answer ?: NOT_FOUND_BODY).toResponseBody(JSON))
            .build()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val NOT_FOUND_BODY = """{"detail":"Not Found"}"""
    }
}
