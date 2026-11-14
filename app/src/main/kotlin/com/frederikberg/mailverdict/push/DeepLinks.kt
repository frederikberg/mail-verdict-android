package com.frederikberg.mailverdict.push

import com.frederikberg.mailverdict.kit.push.PushTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A destination the app was asked to open from outside (a notification tap) and has not yet shown.
 * The activity offers it, whichever screen is on top consumes it once, so a rotation or a recreated
 * activity never replays it.
 */
class DeepLinks {
    private val _pending = MutableStateFlow<PushTarget?>(null)
    val pending: StateFlow<PushTarget?> = _pending.asStateFlow()

    fun offer(target: PushTarget?) {
        if (target != null) _pending.value = target
    }

    fun consume(target: PushTarget) {
        _pending.value = _pending.value.takeIf { it != target }
    }
}
