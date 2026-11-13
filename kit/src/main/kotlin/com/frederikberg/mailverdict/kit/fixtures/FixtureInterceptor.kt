package com.frederikberg.mailverdict.kit.fixtures

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Answers requests from canned JSON instead of the network, so a screen can be rendered and a
 * store exercised without a server. The same instance serves unit tests and the app's fixture mode.
 *
 * [routes] maps `"METHOD /path"` (no query string) to the response body. An unknown route answers
 * 404 with a JSON body, which is what an unmocked request to the real server looks like to a caller.
 */
class FixtureInterceptor(
    private val routes: Map<String, String>,
) : Interceptor {
    private val seen = mutableListOf<String>()

    /** Every `"METHOD /path"` this interceptor was asked for, oldest first. */
    val requests: List<String>
        get() = synchronized(seen) { seen.toList() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = "${request.method} ${request.url.encodedPath}"
        synchronized(seen) { seen += key }

        val body = routes[key]
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (body == null) 404 else 200)
            .message(if (body == null) "Not Found" else "OK")
            .body((body ?: NOT_FOUND_BODY).toResponseBody(JSON))
            .build()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val NOT_FOUND_BODY = """{"detail":"Not Found"}"""
    }
}
