package com.frederikberg.mailverdict.kit.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The PKCE (RFC 7636) half of the authorization-code sign-in: a secret the app invents and the
 * SHA-256 challenge of it that travels in the browser redirect, so an intercepted authorization code
 * is useless to anything that never saw the secret.
 */
object Pkce {
    data class Codes(
        val verifier: String,
        val challenge: String,
    )

    private val random = SecureRandom()
    private val base64Url = Base64.getUrlEncoder().withoutPadding()

    /** A fresh verifier (43 characters of 256 random bits) with its `S256` challenge. */
    fun makePair(): Codes {
        val verifier = randomUrlSafeString()
        return Codes(verifier, challengeFor(verifier))
    }

    /** `BASE64URL(SHA256(ASCII(verifier)))`, unpadded. */
    fun challengeFor(verifier: String): String =
        base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Unpadded base64url of [byteCount] random bytes: the `state` parameter, and the verifier. */
    fun randomUrlSafeString(
        byteCount: Int = 32,
    ): String = base64Url.encodeToString(ByteArray(byteCount).also(random::nextBytes))
}
