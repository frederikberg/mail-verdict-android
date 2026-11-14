package com.frederikberg.mailverdict.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One sign-in of the caller (a phone, browsers, a machine client); [current] is the one making the request. */
@Serializable
data class SignInResponse(
    val id: String,
    @SerialName("user_id") val userId: String,
    val kind: SignInKind,
    val name: String? = null,
    val subject: String,
    @SerialName("first_seen_at") val firstSeenAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
    val current: Boolean,
)
