package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.contract.ContractJson
import com.frederikberg.mailverdict.kit.auth.SealedFile
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Base64
import javax.crypto.SecretKey

/**
 * What this phone keeps of its native push registration. [contentKey] is a secret (the server seals
 * every alert with it), so the record lives in a [SealedFile], never in plain preferences.
 */
@Serializable
data class NativePushRecord(
    val serverUrl: String,
    val installationId: String,
    /** Standard base64 of the 32-byte key the phone generated for this registration. */
    val contentKey: String,
    val subscriptionId: String,
    val relayUrl: String,
    /** The platform push token the relay's ticket was issued for; a different one means registering again. */
    val pushToken: String,
    /** When the relay's ticket stops working, epoch milliseconds. */
    val ticketExpiresAtMs: Long,
) {
    val contentKeyBytes: ByteArray get() = Base64.getDecoder().decode(contentKey)

    override fun toString(): String =
        "NativePushRecord(serverUrl=$serverUrl, installationId=$installationId, subscriptionId=$subscriptionId)"
}

interface NativePushRecordStore {
    fun read(): NativePushRecord?

    fun write(record: NativePushRecord)

    fun clear()
}

class InMemoryNativePushRecordStore(
    private var record: NativePushRecord? = null,
) : NativePushRecordStore {
    override fun read() = record

    override fun write(record: NativePushRecord) {
        this.record = record
    }

    override fun clear() {
        record = null
    }
}

/** Keeps the record in a file sealed under the platform's key; anything unreadable reads as no registration. */
class SealedFileNativePushRecordStore(
    file: File,
    key: () -> SecretKey,
) : NativePushRecordStore {
    private val sealed = SealedFile(file, key)

    @Synchronized
    override fun read(): NativePushRecord? =
        runCatching {
            ContractJson.decodeFromString(NativePushRecord.serializer(), sealed.read()?.decodeToString() ?: return null)
        }.getOrNull()

    @Synchronized
    override fun write(record: NativePushRecord) =
        sealed.write(ContractJson.encodeToString(NativePushRecord.serializer(), record).toByteArray())

    @Synchronized
    override fun clear() = sealed.delete()
}
