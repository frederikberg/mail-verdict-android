package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.contract.ContactSearchHitOut
import com.frederikberg.mailverdict.kit.net.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The address suggestions under the recipient field being typed in, matched by the server. A query
 * waits [debounceMs] for typing to pause, and a newer one replaces it; a failed search shows nothing.
 */
class RecipientSuggestions(
    private val search: suspend (String) -> List<ContactSearchHitOut>,
    scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val query = MutableStateFlow("")
    private val _hits = MutableStateFlow<List<ContactSearchHitOut>>(emptyList())
    val hits: StateFlow<List<ContactSearchHitOut>> = _hits.asStateFlow()

    init {
        scope.launch {
            query.collectLatest { text ->
                val trimmed = text.trim()
                if (trimmed.isEmpty()) {
                    _hits.value = emptyList()
                    return@collectLatest
                }
                delay(debounceMs)
                _hits.value =
                    try {
                        search(trimmed).take(MAX_SHOWN)
                    } catch (_: ApiError) {
                        emptyList()
                    }
            }
        }
    }

    fun query(text: String) {
        query.value = text
    }

    fun clear() {
        query.value = ""
        _hits.value = emptyList()
    }

    companion object {
        const val DEBOUNCE_MS = 200L
        const val MAX_SHOWN = 8
    }
}
