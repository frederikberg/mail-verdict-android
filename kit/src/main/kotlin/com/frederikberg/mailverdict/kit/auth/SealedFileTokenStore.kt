package com.frederikberg.mailverdict.kit.auth

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the credential in one file, sealed with AES-GCM under a key the platform guards (on Android,
 * a non-exportable key in the AndroidKeyStore, so the file is useless off the device or to another
 * app). The file is `iv ‖ ciphertext+tag`.
 *
 * Anything unreadable is treated as no credential at all: a file that was altered, truncated or
 * sealed under a key the keystore no longer holds (restored backup, reset lock screen) leaves the
 * person signed out rather than crashing the launch.
 */
class SealedFileTokenStore(
    private val file: File,
    /** Called on every use; the platform hands back the same key each time. */
    private val key: () -> SecretKey,
) : TokenStore {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun read(): AuthMode? =
        runCatching {
            if (!file.exists()) return null
            val sealed = file.readBytes()
            if (sealed.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            val plain = cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
            json.decodeFromString(AuthMode.serializer(), plain.decodeToString())
        }.getOrNull()

    @Synchronized
    override fun write(mode: AuthMode) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The keystore insists on choosing the IV itself, so it is read back rather than supplied.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(json.encodeToString(AuthMode.serializer(), mode).toByteArray())
        file.absoluteFile.parentFile?.mkdirs()
        // Replaced whole through a rename, so a crash mid-write cannot leave half a token set behind
        // (a rotated refresh token lost that way would end the session).
        val temporary = File(file.absolutePath + ".tmp")
        temporary.writeBytes(sealed)
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
