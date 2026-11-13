package com.frederikberg.mailverdict.kit.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    /** RFC 7636 appendix B's own vector, so the challenge is not checked against the SHA-256 call the implementation makes. */
    @Test
    fun theChallengeMatchesTheRfcVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun aGeneratedVerifierIsWithinTheAllowedLengthAndAlphabet() {
        val pair = Pkce.makePair()
        assertTrue(pair.verifier.length in 43..128)
        val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')
        assertTrue(pair.verifier.all { it in allowed })
        assertEquals(Pkce.challengeFor(pair.verifier), pair.challenge)
    }

    @Test
    fun everyPairAndStateIsDifferent() {
        assertNotEquals(Pkce.makePair().verifier, Pkce.makePair().verifier)
        assertNotEquals(Pkce.randomUrlSafeString(), Pkce.randomUrlSafeString())
    }
}
