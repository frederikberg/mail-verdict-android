package com.frederikberg.mailverdict.kit

import com.frederikberg.mailverdict.contract.AuthConfigResponse
import com.frederikberg.mailverdict.kit.auth.OidcTokens
import mockwebserver3.MockResponse

/** One provider and one API served by the same [TestServer]; the issuer is the server itself. */
object OidcFixtures {
    const val CLIENT_ID = "native-client"
    const val DISCOVERY = "/.well-known/openid-configuration"
    const val TOKEN = "/oauth/v2/token"

    fun config(issuer: String) =
        AuthConfigResponse(
            issuer = issuer,
            clientId = CLIENT_ID,
            scopes = "openid profile email offline_access",
            redirectUri = "mailverdict://auth/callback",
        )

    fun tokens(
        issuer: String,
        access: String = "access-1",
        refresh: String? = "refresh-1",
        expiresAtMs: Long? = null,
    ) = OidcTokens(
        accessToken = access,
        refreshToken = refresh,
        expiresAtMs = expiresAtMs,
        issuer = issuer,
        clientId = CLIENT_ID,
    )

    fun discovery(
        server: TestServer,
        issuer: String = server.baseUrl,
    ): MockResponse =
        TestServer.json(
            """{"issuer":"$issuer","authorization_endpoint":"${server.baseUrl}/oauth/v2/authorize","token_endpoint":"${server.baseUrl}$TOKEN"}""",
        )

    fun tokenAnswer(
        access: String,
        refresh: String?,
        expiresIn: Long = 3600,
        delayMs: Long = 0,
    ): MockResponse {
        val refreshField = refresh?.let { ""","refresh_token":"$it"""" }.orEmpty()
        return TestServer.json("""{"access_token":"$access"$refreshField,"expires_in":$expiresIn}""", delayMs = delayMs)
    }

    fun tokenRefusal(
        code: Int = 400,
        error: String = "invalid_grant",
    ) = TestServer.json("""{"error":"$error"}""", code)

    const val ME =
        """{"user_id":"00000000-0000-0000-0000-000000000001","email":null,"display_name":"Owner","role":"admin","disabled":false}"""

    fun bodyOf(request: mockwebserver3.RecordedRequest): String = request.body?.utf8().orEmpty()
}
