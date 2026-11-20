package com.frederikberg.mailverdict.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One address `GET /contacts/search` offers the composer: a contact with three addresses is three hits. */
@Serializable
data class ContactSearchHitOut(
    @SerialName("contact_id") val contactId: String,
    val name: String,
    val email: String,
    val source: ContactSource,
)

/** Where a suggested address comes from: an address book, mail recently exchanged, or one typed before. */
@Serializable
@JvmInline
value class ContactSource(
    val raw: String,
) {
    val isKnown: Boolean get() = raw in known

    companion object {
        val Contact = ContactSource("contact")
        val Recent = ContactSource("recent")
        val Typed = ContactSource("typed")
        val known: Set<String> = setOf(Contact.raw, Recent.raw, Typed.raw)
    }
}
