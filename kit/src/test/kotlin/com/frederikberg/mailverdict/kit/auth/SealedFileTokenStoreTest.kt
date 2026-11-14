package com.frederikberg.mailverdict.kit.auth

import com.frederikberg.mailverdict.kit.OidcFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SealedFileTokenStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val file get() = folder.root.resolve("auth.bin")

    private fun store(with: SecretKey = key) = SealedFileTokenStore(file) { with }

    private fun secret(access: String) =
        AuthMode.Oidc(
            OidcFixtures.tokens("https://issuer.example", access, refresh = null),
        )

    @Test
    fun aTokenSetSurvivesARestart() {
        val tokens = OidcFixtures.tokens("https://issuer.example", access = "access-1", refresh = "refresh-1")
        store().write(AuthMode.Oidc(tokens))

        assertEquals(AuthMode.Oidc(tokens), store().read())
    }

    @Test
    fun anAccessTokenOnlySetSurvivesARestart() {
        store().write(secret("pasted-secret"))

        assertEquals(secret("pasted-secret"), store().read())
    }

    /** What an earlier version stored for a pasted token: it reads as signed out, so the long-lived token is dropped. */
    @Test
    fun aStoredPastedTokenReadsAsNothing() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        file.writeBytes(cipher.iv + cipher.doFinal("""{"type":"bearer","token":"pasted-secret"}""".toByteArray()))

        assertNull(store().read())
    }

    @Test
    fun theFileNeverHoldsTheTokenInTheClear() {
        store().write(secret("pasted-secret"))

        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("pasted-secret"))
    }

    @Test
    fun nothingStoredReadsAsNothing() {
        assertNull(store().read())
    }

    @Test
    fun aFileSealedUnderAnotherKeyReadsAsNothing() {
        store().write(secret("pasted-secret"))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertNull(store(other).read())
    }

    @Test
    fun anAlteredFileReadsAsNothing() {
        store().write(secret("pasted-secret"))
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertNull(store().read())
    }

    @Test
    fun aTruncatedFileReadsAsNothing() {
        file.writeBytes(ByteArray(5))

        assertNull(store().read())
    }

    @Test
    fun writingReplacesWhatWasThere() {
        store().write(secret("first"))
        store().write(secret("second"))

        assertEquals(secret("second"), store().read())
        assertFalse(folder.root.resolve("auth.bin.tmp").exists())
    }

    @Test
    fun clearingRemovesTheCredential() {
        store().write(secret("pasted-secret"))
        store().clear()

        assertNull(store().read())
        assertTrue(!file.exists())
    }
}
