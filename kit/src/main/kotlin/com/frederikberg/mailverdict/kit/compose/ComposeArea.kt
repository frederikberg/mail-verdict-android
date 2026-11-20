package com.frederikberg.mailverdict.kit.compose

import com.frederikberg.mailverdict.kit.session.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The composer's stores for one connection: one [ComposerStore] per open composer, so a rotation
 * keeps what was typed, and the undo-send store, which outlives every composer.
 */
class ComposeArea(
    connection: Connection,
    private val scope: CoroutineScope,
    /** Null in a run that keeps nothing on the device. */
    private val recovery: ComposeRecovery?,
    private val backend: ApiComposerBackend = ApiComposerBackend(connection.api),
) {
    private val composers = HashMap<String, ComposerStore>()
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** What a closed composer tells the person ("Draft saved", "Send cancelled"), shown wherever they are. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    val undoSend: UndoSendStore =
        UndoSendStore(backend, scope).also {
            it.follow(connection.liveEventHub.events)
        }

    @Synchronized
    fun composer(intent: ComposeIntent): ComposerStore =
        composers.getOrPut(intent.id) {
            val restoration =
                (intent.kind as? ComposeIntent.Kind.UndoRestore)?.let {
                    undoSend.restoration(
                        it.pendingSendId,
                    )
                }
            ComposerStore(intent, backend, scope, recovery, restoration).also { it.startRecovery() }
        }

    /** Suggestions live in the screen's own [screenScope], so they end with it. */
    fun suggestions(
        screenScope: CoroutineScope,
    ): RecipientSuggestions = RecipientSuggestions(backend::searchContacts, screenScope)

    /** Drops a closed composer, and the files a restored send was holding for it. */
    @Synchronized
    fun close(intent: ComposeIntent) {
        composers.remove(intent.id)?.stop()
        (intent.kind as? ComposeIntent.Kind.UndoRestore)?.let { undoSend.forget(it.pendingSendId) }
    }

    fun notify(message: String) {
        _notices.tryEmit(message)
    }
}
