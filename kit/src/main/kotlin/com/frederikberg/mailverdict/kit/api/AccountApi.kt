package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.AccountCreateRequest
import com.frederikberg.mailverdict.contract.AccountResponse
import com.frederikberg.mailverdict.contract.AccountUpdateRequest
import com.frederikberg.mailverdict.contract.EmojiUpdate
import com.frederikberg.mailverdict.contract.IdentityCreate
import com.frederikberg.mailverdict.contract.IdentityResponse
import com.frederikberg.mailverdict.contract.IdentityUpdate
import com.frederikberg.mailverdict.kit.net.ApiClient

suspend fun ApiClient.createAccount(request: AccountCreateRequest): AccountResponse =
    send(
        AccountResponse.serializer(),
        "/accounts",
        method = "POST",
        body = encode(AccountCreateRequest.serializer(), request),
    )

suspend fun ApiClient.updateAccount(
    accountId: String,
    request: AccountUpdateRequest,
): AccountResponse =
    send(
        AccountResponse.serializer(),
        "/accounts/$accountId",
        method = "PATCH",
        body = encode(AccountUpdateRequest.serializer(), request),
    )

/** Removes the account and its mirrored mailbox; nothing is touched on the mail server. */
suspend fun ApiClient.deleteAccount(accountId: String) = sendNoContent("/accounts/$accountId", "DELETE")

/** Sets the emoji that marks an account, or removes it with null. */
suspend fun ApiClient.setAccountEmoji(
    accountId: String,
    emoji: String?,
) {
    sendNoContent("/accounts/$accountId/emoji", "PUT", body = encode(EmojiUpdate.serializer(), EmojiUpdate(emoji)))
}

suspend fun ApiClient.createIdentity(request: IdentityCreate): IdentityResponse =
    send(
        IdentityResponse.serializer(),
        "/identities",
        method = "POST",
        body = encode(IdentityCreate.serializer(), request),
    )

suspend fun ApiClient.updateIdentity(
    identityId: String,
    request: IdentityUpdate,
): IdentityResponse =
    send(
        IdentityResponse.serializer(),
        "/identities/$identityId",
        method = "PATCH",
        body = encode(IdentityUpdate.serializer(), request),
    )

suspend fun ApiClient.deleteIdentity(identityId: String) = sendNoContent("/identities/$identityId", "DELETE")
