package com.frederikberg.mailverdict.kit.push

import com.frederikberg.mailverdict.contract.ContractJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * The plaintext inside a sealed native push: one alert, plus the ids of alerts the server wants a phone
 * to withdraw if it is still showing them.
 */
@Serializable
data class NativeAlert(
    @SerialName("alert_id") val alertId: String,
    val kind: String? = null,
    val title: String = "",
    val body: String? = null,
    val url: String? = null,
    val resolved: List<String> = emptyList(),
) {
    /** The notification to show, tagged with the alert id so a second push for the same alert replaces the first. */
    fun toPayload() = PushPayload(title = title.ifBlank { null }, body = body, url = url, tag = alertId)

    companion object {
        fun parse(plaintext: ByteArray): NativeAlert =
            try {
                ContractJson.decodeFromString(serializer(), plaintext.decodeToString())
            } catch (e: SerializationException) {
                throw PushEnvelopeException("The sealed alert is not the expected JSON.")
            } catch (e: IllegalArgumentException) {
                throw PushEnvelopeException("The sealed alert is not the expected JSON.")
            }
    }
}
