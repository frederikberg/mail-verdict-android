package com.frederikberg.mailverdict.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * An address header the server stores untyped: a single string, an array of strings, or nothing,
 * depending on how the message arrived. Whatever arrives decodes to the list of addresses it names;
 * a shape this app does not know decodes to an empty list rather than failing the whole message.
 */
@Serializable(with = AddressListSerializer::class)
data class AddressList(
    val addresses: List<String>,
)

object AddressListSerializer : KSerializer<AddressList> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): AddressList {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        val addresses =
            when (element) {
                is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                is JsonNull -> emptyList()
                is JsonPrimitive -> if (element.isString) listOf(element.content) else emptyList()
                else -> emptyList()
            }
        return AddressList(addresses)
    }

    override fun serialize(
        encoder: Encoder,
        value: AddressList,
    ) {
        (encoder as JsonEncoder).encodeJsonElement(
            buildJsonArray { value.addresses.forEach { add(JsonPrimitive(it)) } },
        )
    }
}
