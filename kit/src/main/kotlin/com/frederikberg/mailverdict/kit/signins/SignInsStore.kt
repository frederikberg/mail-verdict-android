package com.frederikberg.mailverdict.kit.signins

import com.frederikberg.mailverdict.contract.SignInResponse
import com.frederikberg.mailverdict.kit.api.listOwnSignIns
import com.frederikberg.mailverdict.kit.api.revokeOwnSignIn
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import com.frederikberg.mailverdict.kit.net.writeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SignInsState(
    val signIns: List<SignInResponse> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    /** Ids whose revoke is on its way. */
    val revoking: Set<String> = emptySet(),
)

/**
 * The person's own sign-ins: this phone, other phones, the web browsers together, machine clients.
 *
 * Signing out another sign-in is one request. Signing out the one making the requests is the
 * session's own sign-out, handed in as [signOutThisDevice]: it also removes the phone's push
 * subscription, forgets the credential and leaves the screen, none of which this store owns.
 */
class SignInsStore(
    private val api: ApiClient,
    private val signOutThisDevice: () -> Unit,
) {
    private val _state = MutableStateFlow(SignInsState())
    val state: StateFlow<SignInsState> = _state.asStateFlow()

    /** Live ones first, as the server lists them. Keeps what is on screen when the read fails, and says so. */
    suspend fun load() {
        _state.update { it.copy(isLoading = !it.hasLoaded, errorMessage = null) }
        try {
            val signIns = api.listOwnSignIns()
            _state.update { it.copy(signIns = signIns, isLoading = false, hasLoaded = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { it.copy(isLoading = false, errorMessage = e.userMessage) }
        }
    }

    suspend fun revoke(signIn: SignInResponse): WriteOutcome {
        if (signIn.current) {
            signOutThisDevice()
            return WriteOutcome.Done
        }
        _state.update { it.copy(revoking = it.revoking + signIn.id) }
        val outcome = writeOutcome { api.revokeOwnSignIn(signIn.id) }
        _state.update { it.copy(revoking = it.revoking - signIn.id) }
        if (outcome.isDone) load()
        return outcome
    }
}
