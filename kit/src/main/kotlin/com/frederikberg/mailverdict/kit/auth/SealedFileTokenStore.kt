package com.frederikberg.mailverdict.kit.auth

import kotlinx.serialization.json.Json
import java.io.File
import javax.crypto.SecretKey

/**
 * Keeps the credential in one [SealedFile]. Anything unreadable is treated as no credential at all:
 * a file that was altered, truncated or sealed under a key the keystore no longer holds leaves the
 * person signed out rather than crashing the launch, as does a credential of a kind this version no
 * longer reads.
 */
class SealedFileTokenStore(
    file: File,
    key: () -> SecretKey,
) : TokenStore {
    private val sealed = SealedFile(file, key)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    override fun read(): AuthMode? =
        runCatching {
            json.decodeFromString(AuthMode.serializer(), sealed.read()?.decodeToString() ?: return null)
        }.getOrNull()

    @Synchronized
    override fun write(mode: AuthMode) = sealed.write(json.encodeToString(AuthMode.serializer(), mode).toByteArray())

    @Synchronized
    override fun clear() = sealed.delete()
}
