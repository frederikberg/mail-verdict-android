package com.frederikberg.mailverdict.kit.net

import com.frederikberg.mailverdict.kit.OidcFixtures
import com.frederikberg.mailverdict.kit.auth.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that are easy to break and silent when broken: the scheme guard, the base join, the
 * query escaping, and reading the credential at send time rather than at construction.
 */
class RequestFactoryTest {
    private fun factory(
        base: String = "https://mail.example.com",
        mode: () -> AuthMode = { AuthMode.None },
    ) = RequestFactory(base, mode)

    @Test
    fun aNonHttpSchemeIsRefused() {
        val error =
            assertThrows(
                RequestFactory.ConfigurationError.UnsupportedScheme::class.java,
            ) { factory("ftp://example.com") }
        assertEquals("ftp", error.scheme)
        assertThrows(RequestFactory.ConfigurationError.UnsupportedScheme::class.java) { factory("mail.example.com") }
    }

    @Test
    fun anEmptyAddressIsRefused() {
        assertThrows(RequestFactory.ConfigurationError.EmptyBaseUrl::class.java) { factory("   ") }
    }

    @Test
    fun theApiPrefixIsAddedAndATrailingSlashDoesNotDoubleUp() {
        assertEquals(
            "https://mail.example.com/api/health",
            factory("https://mail.example.com/").build("/health").url.toString(),
        )
        assertEquals(
            "https://mail.example.com/api/health",
            factory("https://mail.example.com").build("/health").url.toString(),
        )
    }

    @Test
    fun aServerMountedUnderAPathKeepsItsPrefix() {
        assertEquals(
            "https://example.com/mail/api/health",
            factory("https://example.com/mail/").build("/health").url.toString(),
        )
    }

    /** The credential is read per request, so a renewed token takes effect on the next request rather than the next launch. */
    @Test
    fun theCredentialIsReadAtSendTime() {
        var mode: AuthMode = AuthMode.None
        val factory = factory(mode = { mode })
        assertNull(factory.build("/health").header("Authorization"))

        mode = AuthMode.Bearer("jwt-value")
        assertEquals("Bearer jwt-value", factory.build("/health").header("Authorization"))
    }

    @Test
    fun anEmptyBearerSendsNoAuthorizationHeader() {
        assertNull(factory(mode = { AuthMode.Bearer("") }).build("/health").header("Authorization"))
    }

    @Test
    fun anOidcSessionSendsTheAccessTokenAsBearer() {
        val request =
            factory(
                mode = { AuthMode.Oidc(OidcFixtures.tokens("https://i", access = "access")) },
            ).build("/health")
        assertEquals("Bearer access", request.header("Authorization"))
        assertEquals("access", RequestFactory.bearerOf(request))
    }

    /** A literal `+` is legal in a query, but a server decoding it as a form reads a space: a search for `a+b` must stay `a+b`. */
    @Test
    fun aPlusInAQueryValueIsEscaped() {
        val url = factory().build("/search", query = listOf("q" to "a+b c")).url.toString()
        assertTrue(url, url.contains("q=a%2Bb%20c"))
        assertFalse(url, url.contains("q=a+b"))
    }

    /** A path segment that already carries an escape must not be escaped again (`%2F` becoming `%252F`). */
    @Test
    fun aPreEncodedPathSegmentIsNotEncodedTwice() {
        val url = factory().build("/folders/a%2Fb").url.toString()
        assertTrue(url, url.endsWith("/api/folders/a%2Fb"))
        assertFalse(url, url.contains("%25"))
    }

    @Test
    fun aBodyCarriesItsContentTypeAndAWriteWithoutOneStillSendsAnEmptyBody() {
        val withBody = factory().build("/outbox", "POST", body = "{}".toByteArray(), contentType = "application/json")
        assertEquals("application/json", withBody.body?.contentType()?.toString())
        val empty = factory().build("/alerts/dismiss-all", "POST")
        assertEquals(0L, empty.body?.contentLength())
    }
}
