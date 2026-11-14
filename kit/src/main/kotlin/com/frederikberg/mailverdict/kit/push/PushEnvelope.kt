package com.frederikberg.mailverdict.kit.push

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A sealed push blob that is malformed, of an unknown version, or does not open under this device's key. */
class PushEnvelopeException(
    message: String,
) : Exception(message)

/**
 * Opens the sealed blob a native push carries. The server seals every alert with the content key this
 * phone generated, so neither the push relay nor Google can read it.
 *
 * `blob = base64(0x01 ‖ nonce(12) ‖ AES-256-GCM ciphertext ‖ tag(16))`, with the additional
 * authenticated data `"mv-push-v1:"` plus the installation id in its lowercase hyphenated form, so a
 * blob sealed for one device never opens on another.
 */
object PushEnvelope {
    const val CONTENT_KEY_BYTES = 32
    private const val VERSION = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val AAD_PREFIX = "mv-push-v1:"

    fun open(
        blob: String,
        contentKey: ByteArray,
        installationId: String,
    ): ByteArray {
        require(contentKey.size == CONTENT_KEY_BYTES) { "the content key is $CONTENT_KEY_BYTES bytes" }
        val raw =
            try {
                Base64.getDecoder().decode(blob)
            } catch (_: IllegalArgumentException) {
                throw PushEnvelopeException("The blob is not base64.")
            }
        if (raw.size < 1 + NONCE_BYTES + TAG_BYTES || raw[0].toInt() != VERSION) {
            throw PushEnvelopeException("The blob is truncated or of an unknown version.")
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(contentKey, "AES"),
                GCMParameterSpec(TAG_BYTES * 8, raw, 1, NONCE_BYTES),
            )
            cipher.updateAAD((AAD_PREFIX + installationId.lowercase()).toByteArray(Charsets.US_ASCII))
            cipher.doFinal(raw, 1 + NONCE_BYTES, raw.size - 1 - NONCE_BYTES)
        } catch (_: java.security.GeneralSecurityException) {
            throw PushEnvelopeException("The blob does not authenticate under this key.")
        }
    }
}
