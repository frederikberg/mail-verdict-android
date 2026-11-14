package com.frederikberg.mailverdict.kit.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedirectUriTest {
    private val expected = "mailverdict://auth/callback"

    @Test
    fun theRedirectWithItsAnswerMatches() {
        assertTrue(RedirectUri.matches(expected, "mailverdict://auth/callback?code=c&state=s"))
    }

    @Test
    fun schemeAndHostIgnoreCase() {
        assertTrue(RedirectUri.matches(expected, "MailVerdict://AUTH/callback?code=c"))
    }

    @Test
    fun anotherSchemeHostOrPathDoesNot() {
        assertFalse(RedirectUri.matches(expected, "https://auth/callback?code=c"))
        assertFalse(RedirectUri.matches(expected, "mailverdict://evil/callback?code=c"))
        assertFalse(RedirectUri.matches(expected, "mailverdict://auth/other?code=c"))
        assertFalse(RedirectUri.matches(expected, "mailverdict://auth/callback/extra?code=c"))
    }

    @Test
    fun somethingThatIsNotAUriDoesNot() {
        assertFalse(RedirectUri.matches(expected, "not a uri"))
    }
}
