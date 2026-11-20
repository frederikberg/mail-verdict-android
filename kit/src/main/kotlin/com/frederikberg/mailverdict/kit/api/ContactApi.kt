package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.ContactSearchHitOut
import com.frederikberg.mailverdict.kit.net.ApiClient
import kotlinx.serialization.builtins.ListSerializer

/** The composer's recipient suggestions: one hit per address, matched by the server. */
suspend fun ApiClient.searchContacts(query: String): List<ContactSearchHitOut> =
    send(ListSerializer(ContactSearchHitOut.serializer()), "/contacts/search", query = query("q" to query))
