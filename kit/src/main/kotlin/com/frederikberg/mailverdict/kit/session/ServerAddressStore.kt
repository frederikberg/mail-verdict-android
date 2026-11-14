package com.frederikberg.mailverdict.kit.session

/** Where the server address is remembered between launches. Not a secret, so it needs no sealing. */
interface ServerAddressStore {
    fun read(): String?

    fun write(address: String)
}

class InMemoryServerAddressStore(
    initial: String? = null,
) : ServerAddressStore {
    @Volatile
    private var address: String? = initial

    override fun read(): String? = address

    override fun write(address: String) {
        this.address = address
    }
}
