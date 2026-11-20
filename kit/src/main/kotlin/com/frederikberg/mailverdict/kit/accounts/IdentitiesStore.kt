package com.frederikberg.mailverdict.kit.accounts

import com.frederikberg.mailverdict.contract.IdentityCreate
import com.frederikberg.mailverdict.contract.IdentityResponse
import com.frederikberg.mailverdict.contract.IdentityUpdate
import com.frederikberg.mailverdict.kit.api.createIdentity
import com.frederikberg.mailverdict.kit.api.deleteIdentity
import com.frederikberg.mailverdict.kit.api.listIdentities
import com.frederikberg.mailverdict.kit.api.updateIdentity
import com.frederikberg.mailverdict.kit.live.LiveEvent
import com.frederikberg.mailverdict.kit.live.LiveInvalidation
import com.frederikberg.mailverdict.kit.net.ApiClient
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.net.WriteOutcome
import com.frederikberg.mailverdict.kit.net.writeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IdentitiesState(
    val identities: List<IdentityResponse> = emptyList(),
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
)

/** The addresses one account may send as: listed, added, made the default, removed. */
class IdentitiesStore(
    private val api: ApiClient,
    private val scope: CoroutineScope,
    private val accountId: String,
) {
    private val _state = MutableStateFlow(IdentitiesState())
    val state: StateFlow<IdentitiesState> = _state.asStateFlow()

    suspend fun load() {
        try {
            val identities = api.listIdentities(accountId)
            _state.update { it.copy(identities = identities, hasLoaded = true, errorMessage = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            _state.update { it.copy(errorMessage = e.userMessage) }
        }
    }

    /** The account's first identity is the default whatever is asked; a later one only when [makeDefault]. */
    suspend fun add(
        address: String,
        displayName: String,
        makeDefault: Boolean = false,
    ): WriteOutcome {
        val trimmed = address.trim()
        if (!looksLikeAddress(trimmed)) return WriteOutcome.Failed("Enter the address as name@example.com.")
        val outcome =
            writeOutcome {
                api.createIdentity(
                    IdentityCreate(
                        accountId = accountId,
                        address = trimmed,
                        displayName = displayName.trim().ifEmpty { null },
                        isDefault = makeDefault || _state.value.identities.isEmpty(),
                    ),
                )
            }
        if (outcome.isDone) load()
        return outcome
    }

    suspend fun makeDefault(identityId: String): WriteOutcome {
        val outcome = writeOutcome { api.updateIdentity(identityId, IdentityUpdate(isDefault = true)) }
        if (outcome.isDone) load()
        return outcome
    }

    suspend fun remove(identityId: String): WriteOutcome {
        val outcome = writeOutcome { api.deleteIdentity(identityId) }
        if (outcome.isDone) load()
        return outcome
    }

    /** `identity.changed` for this account, or a resync, re-reads the list. */
    fun follow(events: Flow<List<LiveEvent>>) {
        scope.launch {
            events.collect { batch ->
                val stale =
                    batch.any {
                        val invalidation = it.invalidation
                        invalidation is LiveInvalidation.Resync ||
                            (
                                invalidation is LiveInvalidation.IdentitiesChanged &&
                                    (invalidation.accountId == null || invalidation.accountId == accountId)
                            )
                    }
                if (stale && _state.value.hasLoaded) load()
            }
        }
    }
}
