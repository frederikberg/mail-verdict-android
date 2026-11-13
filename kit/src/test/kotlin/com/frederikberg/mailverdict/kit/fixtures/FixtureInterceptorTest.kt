package com.frederikberg.mailverdict.kit.fixtures

import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.contract.MessageLocation
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class FixtureInterceptorTest {
    private val interceptor = FixtureInterceptor(Fixtures.routes)
    private val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun get(path: String) =
        client
            .newCall(
                Request.Builder().url("http://fixture.invalid$path").build(),
            ).execute()

    @Test
    fun aKnownRouteDecodesIntoItsContractModel() {
        val body = get("/messages/00000000-0000-4000-8000-000000000001/location").use { it.body.string() }

        val location = ContractJson.decodeFromString(MessageLocation.serializer(), body)

        assertEquals("00000000-0000-4000-8000-0000000000f1", location.folderId)
    }

    @Test
    fun anUnknownRouteIsA404WithAJsonBody() {
        get("/nope").use { response ->
            assertEquals(404, response.code)
            assertEquals("""{"detail":"Not Found"}""", response.body.string())
        }
    }

    @Test
    fun theQueryStringDoesNotChangeTheRoute() {
        get("/health?verbose=1").use { assertEquals(200, it.code) }

        assertEquals(listOf("GET /health"), interceptor.requests)
    }
}
