package com.frederikberg.mailverdict.kit.compose

import java.util.UUID

/** A file attached to the message: picked on the phone, carried from a forwarded original or a reopened draft, or given back by Undo. */
class ComposeAttachment(
    val filename: String,
    val contentType: String?,
    val data: ByteArray,
    val id: String = UUID.randomUUID().toString(),
) {
    val sizeBytes: Int get() = data.size
}
