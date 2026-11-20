@file:OptIn(ExperimentalSerializationApi::class)

package com.frederikberg.mailverdict.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * `POST /accounts`. The port and the two switches are always sent even at their defaults, so what
 * went out is what the person saw on the form.
 */
@Serializable
data class AccountCreateRequest(
    val name: String,
    @SerialName("imap_host") val imapHost: String,
    @EncodeDefault @SerialName("imap_port") val imapPort: Int = DEFAULT_IMAP_PORT,
    @SerialName("imap_user") val imapUser: String,
    @SerialName("imap_password") val imapPassword: String? = null,
    @SerialName("smtp_host") val smtpHost: String? = null,
    @SerialName("smtp_port") val smtpPort: Int? = null,
    @SerialName("smtp_user") val smtpUser: String? = null,
    @SerialName("smtp_password") val smtpPassword: String? = null,
    @EncodeDefault @SerialName("spam_enabled") val spamEnabled: Boolean = false,
    @EncodeDefault @SerialName("orders_enabled") val ordersEnabled: Boolean = false,
    @SerialName("trash_retention_days") val trashRetentionDays: Int? = null,
    @SerialName("junk_retention_days") val junkRetentionDays: Int? = null,
) {
    companion object {
        const val DEFAULT_IMAP_PORT = 993
    }
}

/**
 * `PATCH /accounts/{id}`: a field left out stays as it is. The three day counts can also be cleared,
 * which the server reads only from an explicit `null`; they are [JsonElement]s so that "leave"
 * (Kotlin null, omitted), "clear" ([JsonNull]) and "set" (a number) are three different values, and
 * [days] builds the last two.
 *
 * The IMAP host, port and user are insert-only on the server and have no field here.
 */
@Serializable
data class AccountUpdateRequest(
    val name: String? = null,
    @SerialName("imap_password") val imapPassword: String? = null,
    @SerialName("smtp_host") val smtpHost: String? = null,
    @SerialName("smtp_port") val smtpPort: Int? = null,
    @SerialName("smtp_user") val smtpUser: String? = null,
    @SerialName("smtp_password") val smtpPassword: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("spam_enabled") val spamEnabled: Boolean? = null,
    @SerialName("orders_enabled") val ordersEnabled: Boolean? = null,
    @SerialName("trash_retention_days") val trashRetentionDays: JsonElement? = null,
    @SerialName("junk_retention_days") val junkRetentionDays: JsonElement? = null,
    @SerialName("glacier_enabled") val glacierEnabled: Boolean? = null,
    @SerialName("glacier_auto_days") val glacierAutoDays: JsonElement? = null,
) {
    companion object {
        /** A day count to set, or `null` to clear it back to off. */
        fun days(value: Int?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)
    }
}

/** `PUT /accounts/{id}/emoji`; null removes the emoji. */
@Serializable
data class EmojiUpdate(
    val emoji: String? = null,
)
