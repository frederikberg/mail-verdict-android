package com.frederikberg.mailverdict.kit.auth

import com.frederikberg.mailverdict.contract.AuthConfigResponse
import com.frederikberg.mailverdict.contract.ContractJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStateTest {
    /** What a key-store-backed [TokenStore] writes and reads back: every mode survives its own encoding, refresh token included. */
    @Test
    fun everyAuthModeSurvivesItsJsonEncoding() {
        val modes =
            listOf(
                AuthMode.None,
                AuthMode.Oidc(OidcTokens("a", "r", 1_700_000_000_000, "https://i", "c")),
                AuthMode.Oidc(OidcTokens("a", null, null, "https://i", "c")),
            )
        for (mode in modes) {
            val text = ContractJson.encodeToString(AuthMode.serializer(), mode)
            assertEquals(mode, ContractJson.decodeFromString(AuthMode.serializer(), text))
        }
    }

    @Test
    fun theTokenSetIsMaskedWhenPrinted() {
        val printed = OidcTokens("secret-access", "secret-refresh", null, "https://i", "c").toString()
        assertFalse(printed, "secret-access" in printed || "secret-refresh" in printed)
    }

    @Test
    fun expiryHonoursTheSkew() {
        fun tokens(expiresAtMs: Long?) = OidcTokens("a", null, expiresAtMs, "i", "c")
        assertTrue(tokens(1_000 + 59_000).isExpired(nowMs = 1_000))
        assertFalse(tokens(1_000 + 61_000).isExpired(nowMs = 1_000))
        assertFalse(
            "a token with no stated lifetime is used until the server refuses it",
            tokens(null).isExpired(nowMs = 1_000),
        )
    }

    @Test
    fun aServersSignInConfigIsAskedOncePerAddressAndAFailureIsNotRemembered() =
        runBlocking {
            var asked = 0
            var failNext = true
            val store =
                AuthConfigStore { url ->
                    asked++
                    if (failNext) {
                        failNext = false
                        error("unreachable")
                    }
                    AuthConfigResponse(issuer = url, clientId = "c", scopes = "s", redirectUri = "r")
                }
            assertThrows(IllegalStateException::class.java) { runBlocking { store.config("https://mail.example.com") } }
            assertEquals("https://mail.example.com", store.config("https://mail.example.com").issuer)
            store.config("https://MAIL.example.com/")
            assertEquals("a second look at the same address is answered from memory", 2, asked)
            store.config("https://other.example.com")
            assertEquals(3, asked)
        }
}
