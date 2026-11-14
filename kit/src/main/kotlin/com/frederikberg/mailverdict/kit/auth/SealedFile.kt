package com.frederikberg.mailverdict.kit.auth

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One file sealed with AES-GCM under a key the platform guards (on Android, a non-exportable key in
 * the AndroidKeyStore, so the file is useless off the device or to another app). The file is
 * `iv ‖ ciphertext+tag`.
 *
 * Anything unreadable reads as absent: a file that was altered, truncated or sealed under a key the
 * keystore no longer holds (restored backup, reset lock screen) is the same as no file.
 */
class SealedFile(
    private val file: File,
    /** Called on every use; the platform hands back the same key each time. */
    private val key: () -> SecretKey,
) {
    fun read(): ByteArray? =
        runCatching {
            if (!file.exists()) return null
            val sealed = file.readBytes()
            if (sealed.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }.getOrNull()

    fun write(plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The keystore insists on choosing the IV itself, so it is read back rather than supplied.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain)
        file.absoluteFile.parentFile?.mkdirs()
        // Replaced whole through a rename, so a crash mid-write cannot leave half a file behind.
        val temporary = File(file.absolutePath + ".tmp")
        temporary.writeBytes(sealed)
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    fun delete() {
        file.delete()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
