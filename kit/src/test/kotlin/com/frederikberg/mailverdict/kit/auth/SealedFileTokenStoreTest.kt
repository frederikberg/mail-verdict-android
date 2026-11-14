package com.frederikberg.mailverdict.kit.auth

import com.frederikberg.mailverdict.kit.OidcFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SealedFileTokenStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val file get() = folder.root.resolve("auth.bin")

    private fun store(with: SecretKey = key) = SealedFileTokenStore(file) { with }

    @Test
    fun aTokenSetSurvivesARestart() {
        val tokens = OidcFixtures.tokens("https://issuer.example", access = "access-1", refresh = "refresh-1")
        store().write(AuthMode.Oidc(tokens))

        assertEquals(AuthMode.Oidc(tokens), store().read())
    }

    @Test
    fun aPastedTokenSurvivesARestart() {
        store().write(AuthMode.Bearer("pasted-secret"))

        assertEquals(AuthMode.Bearer("pasted-secret"), store().read())
    }

    @Test
    fun theFileNeverHoldsTheTokenInTheClear() {
        store().write(AuthMode.Bearer("pasted-secret"))

        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("pasted-secret"))
    }

    @Test
    fun nothingStoredReadsAsNothing() {
        assertNull(store().read())
    }

    @Test
    fun aFileSealedUnderAnotherKeyReadsAsNothing() {
        store().write(AuthMode.Bearer("pasted-secret"))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        assertNull(store(other).read())
    }

    @Test
    fun anAlteredFileReadsAsNothing() {
        store().write(AuthMode.Bearer("pasted-secret"))
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
        store().write(AuthMode.Bearer("first"))
        store().write(AuthMode.Bearer("second"))

        assertEquals(AuthMode.Bearer("second"), store().read())
        assertFalse(folder.root.resolve("auth.bin.tmp").exists())
    }

    @Test
    fun clearingRemovesTheCredential() {
        store().write(AuthMode.Bearer("pasted-secret"))
        store().clear()

        assertNull(store().read())
        assertTrue(!file.exists())
    }
}
