package com.frederikberg.mailverdict.kit.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SealedFileNativePushRecordStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val file get() = folder.root.resolve("push.bin")

    private fun store(with: SecretKey = key) = SealedFileNativePushRecordStore(file) { with }

    private val record =
        NativePushRecord(
            "https://mail.example.com",
            "install-1",
            "Y29udGVudC1rZXk=",
            "s1",
            "https://relay",
            "fcm-token-secret",
            5,
        )

    @Test
    fun theRecordSurvivesARestart() {
        store().write(record)

        assertEquals(record, store().read())
    }

    @Test
    fun theKeyAndTheTokenAreNotInTheClearOnDisk() {
        store().write(record)

        val disk = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("Y29udGVudC1rZXk=" in disk)
        assertFalse("fcm-token-secret" in disk)
    }

    @Test
    fun aFileUnderAnotherKeyAndAClearedFileReadAsNothing() {
        store().write(record)
        assertNull(store(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()).read())

        store().clear()
        assertNull(store().read())
    }

    @Test
    fun theSecretsAreNeverPrinted() {
        assertFalse("Y29udGVudC1rZXk=" in record.toString() || "fcm-token-secret" in record.toString())
    }
}
