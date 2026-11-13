package com.frederikberg.mailverdict.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a message is now: `GET /messages/{id}/location`. */
@Serializable
data class MessageLocation(
    val id: String,
    @SerialName("account_id") val accountId: String,
    @SerialName("folder_id") val folderId: String,
    @SerialName("thread_id") val threadId: String,
)
