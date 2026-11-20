package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.IdentityResponse

/**
 * One address the From menu offers: a sending identity, or for an account that has none a stand-in
 * for the address it sends with anyway (`imap_user`, the server's own fallback). The stand-in has no
 * [identityId] and is never sent as `identity_id`, since it names no row that exists.
 */
data class FromAddress(
    val key: String,
    val accountId: String,
    val identityId: String?,
    val address: String,
    val displayName: String?,
    val isDefault: Boolean,
) {
    /** "Name <address>", or the bare address; never the name alone, since several identities may share one. */
    val label: String get() = if (displayName.isNullOrEmpty()) address else "$displayName <$address>"
}

/** The web's `matchIdentity` (`lib/identities.ts`) and the compose dialog's address choices. */
object IdentityRules {
    /** The identity one of [addresses] names, tried in order, so To before Cc prefers a direct match. */
    fun matchIdentity(
        addresses: List<String?>,
        identities: List<IdentityResponse>,
    ): String? {
        val byAddress = HashMap<String, String>()
        identities.forEach { byAddress.putIfAbsent(it.address.lowercase(), it.id) }
        return addresses.firstNotNullOfOrNull { address ->
            address?.takeIf { it.isNotEmpty() }?.let { byAddress[ComposeAddresses.email(it).lowercase()] }
        }
    }

    fun pickable(
        accounts: List<AccountResponse>,
        identities: List<IdentityResponse>,
    ): List<FromAddress> =
        accounts.flatMap { account ->
            val own = identities.filter { it.accountId == account.id }
            own
                .map { FromAddress(it.id, account.id, it.id, it.address, it.displayName, it.isDefault) }
                .ifEmpty {
                    listOf(FromAddress("account-default:${account.id}", account.id, null, account.imapUser, null, true))
                }
        }

    /** The account's starred identity, or its first address. */
    fun defaultAddress(
        addresses: List<FromAddress>,
        accountId: String?,
    ): FromAddress? {
        val forAccount = addresses.filter { it.accountId == accountId }
        return forAccount.firstOrNull { it.isDefault } ?: forAccount.firstOrNull()
    }

    /** The first preferred account that has an address wins; failing all of them, the first address at all. */
    fun resolve(
        addresses: List<FromAddress>,
        preferring: List<String?>,
    ): FromAddress? = preferring.firstNotNullOfOrNull { defaultAddress(addresses, it) } ?: addresses.firstOrNull()
}
