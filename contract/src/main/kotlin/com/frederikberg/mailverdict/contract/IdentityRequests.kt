@file:OptIn(ExperimentalSerializationApi::class)

package com.frederikberg.mailverdict.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `POST /identities`: another address an account may send as. */
@Serializable
data class IdentityCreate(
    @SerialName("account_id") val accountId: String,
    val address: String,
    @SerialName("display_name") val displayName: String? = null,
    @EncodeDefault @SerialName("is_default") val isDefault: Boolean = false,
)

/** `PATCH /identities/{id}`; a field left out stays as it is. */
@Serializable
data class IdentityUpdate(
    val address: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("is_default") val isDefault: Boolean? = null,
)
