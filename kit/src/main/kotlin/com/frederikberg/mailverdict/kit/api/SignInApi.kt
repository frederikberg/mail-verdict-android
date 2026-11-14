package com.frederikberg.mailverdict.kit.api

import com.frederikberg.mailverdict.contract.SignInResponse
import com.frederikberg.mailverdict.kit.net.ApiClient
import kotlinx.serialization.builtins.ListSerializer

/** The caller's sign-ins, live ones first. */
suspend fun ApiClient.listOwnSignIns(): List<SignInResponse> =
    send(ListSerializer(SignInResponse.serializer()), "/me/sign-ins")

/** Revokes one of the caller's sign-ins; the current one included, whose next request is then refused. */
suspend fun ApiClient.revokeOwnSignIn(signInId: String): SignInResponse =
    send(SignInResponse.serializer(), "/me/sign-ins/$signInId/revoke", method = "POST")
