package com.frederikberg.mailverdict.kit.auth

import com.frederikberg.mailverdict.contract.AuthConfigResponse

/**
 * The two halves of an authorization-code sign-in around the browser leg the platform owns: [begin]
 * prepares what the browser opens, [complete] turns what the browser returns into a token set.
 */
class SignInFlow(
    private val oidc: OidcClient,
    private val newState: () -> String = { Pkce.randomUrlSafeString() },
    private val newPkce: () -> Pkce.Codes = { Pkce.makePair() },
) {
    /** One sign-in attempt in progress; held until the browser comes back, never persisted. */
    class Pending internal constructor(
        /** What the browser opens. */
        val authorizationUrl: String,
        internal val state: String,
        internal val pkce: Pkce.Codes,
        internal val config: AuthConfigResponse,
        internal val metadata: OidcProviderMetadata,
    ) {
        /** The address the provider sends the browser back to. */
        val redirectUri: String get() = config.redirectUri
    }

    suspend fun begin(config: AuthConfigResponse): Pending {
        if (config.clientId.isNullOrEmpty()) throw OidcError.SignInUnavailable()
        val metadata = oidc.discover(config.issuer)
        val state = newState()
        val pkce = newPkce()
        return Pending(oidc.authorizationUrl(config, metadata, pkce, state), state, pkce, config, metadata)
    }

    /** [callbackUrl] is the full redirect the browser leg ended with. */
    suspend fun complete(
        pending: Pending,
        callbackUrl: String,
    ): OidcTokens {
        val code = oidc.parseCallback(callbackUrl, expectedState = pending.state)
        return oidc.exchangeCode(code, pending.pkce, pending.config, pending.metadata)
    }
}
