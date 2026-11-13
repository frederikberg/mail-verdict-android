package com.frederikberg.mailverdict.kit.auth

/**
 * Where the credential survives a restart. The real implementation seals it with the platform's
 * key store; tests and fixture mode use [InMemoryTokenStore].
 */
interface TokenStore {
    /** The stored credential, or null when none is stored or it cannot be read. */
    fun read(): AuthMode?

    /** Replaces the stored credential; returns normally only once it is durable. */
    fun write(mode: AuthMode)

    fun clear()
}

class InMemoryTokenStore(
    initial: AuthMode? = null,
) : TokenStore {
    @Volatile
    private var mode: AuthMode? = initial

    override fun read(): AuthMode? = mode

    override fun write(mode: AuthMode) {
        this.mode = mode
    }

    override fun clear() {
        mode = null
    }
}
