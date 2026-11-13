package com.frederikberg.mailverdict.kit.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorTest {
    private fun from(
        status: Int,
        body: String,
    ) = ApiError.from(status, body.toByteArray())

    @Test
    fun theServersDetailIsWhatThePersonReads() {
        assertEquals("account not found", from(404, """{"detail":"account not found"}""").userMessage)
    }

    @Test
    fun aBodyWithoutDetailFallsBackToTheStatusLine() {
        assertTrue(from(502, "<html>").userMessage.startsWith("HTTP 502:"))
        assertTrue(from(500, """{"error":"x"}""").userMessage.startsWith("HTTP 500:"))
        // A detail that is not text (a validation error's list) is not something to show as the message.
        assertTrue(from(422, """{"detail":[{"msg":"bad"}]}""").userMessage.startsWith("HTTP 422:"))
    }

    @Test
    fun aDecodingFailureNeverShowsItsTechnicalText() {
        val error = ApiError.Decoding("Unexpected JSON token at offset 4: …")
        assertFalse(error.userMessage.contains("offset"))
        assertTrue(error.userMessage.isNotBlank())
    }

    /** The one property every call site branches on: was it the credential or the request that was refused. */
    @Test
    fun onlyACredentialRefusalIsAnAuthenticationFailure() {
        assertTrue(from(401, """{"detail":"expired"}""").isAuthenticationFailure)
        assertTrue(from(401, "").isAuthenticationFailure)
        // The login proxy refuses an invalid token with a bare, non-JSON 403.
        assertTrue(from(403, "Forbidden").isAuthenticationFailure)
        // The app's own 403 carries a detail and leaves the person signed in.
        assertFalse(from(403, """{"detail":"admin_required"}""").isAuthenticationFailure)
        assertFalse(from(404, """{"detail":"nope"}""").isAuthenticationFailure)
        assertFalse(ApiError.Transport("offline").isAuthenticationFailure)
        assertFalse(ApiError.Decoding("bad json").isAuthenticationFailure)
        assertFalse(ApiError.ProxyRequiresBrowserLogin().isAuthenticationFailure)
    }

    @Test
    fun forbiddenAndDisabledAreTheAppsOwnRefusals() {
        val forbidden = from(403, """{"detail":"admin_required"}""")
        assertTrue(forbidden.isForbidden)
        assertFalse(forbidden.isDisabled)
        assertFalse(forbidden.endsSession)

        val disabled = from(403, """{"detail":"user_disabled"}""")
        assertTrue(disabled.isForbidden)
        assertTrue(disabled.isDisabled)
        assertTrue(disabled.endsSession)

        assertFalse(from(403, "Forbidden").isForbidden)
        assertFalse(from(401, """{"detail":"user_disabled"}""").isDisabled)
    }

    @Test
    fun theStatusCodeIsKnownOnlyWhenTheServerAnswered() {
        assertEquals(409, from(409, """{"detail":"conflict"}""").statusCode)
        assertEquals(null, ApiError.Transport("offline").statusCode)
    }
}
