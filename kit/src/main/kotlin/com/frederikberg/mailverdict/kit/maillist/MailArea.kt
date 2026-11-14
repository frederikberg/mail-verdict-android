package com.frederikberg.mailverdict.kit.maillist

import com.frederikberg.mailverdict.kit.actions.ActionQueue
import com.frederikberg.mailverdict.kit.actions.ApiIntentTransport
import com.frederikberg.mailverdict.kit.actions.IntentPersistence
import com.frederikberg.mailverdict.kit.actions.MovePicker
import com.frederikberg.mailverdict.kit.actions.MoveTarget
import com.frederikberg.mailverdict.kit.actions.QueueConnectivity
import com.frederikberg.mailverdict.kit.actions.RecentMoveTargets
import com.frederikberg.mailverdict.kit.api.getFolderOrder
import com.frederikberg.mailverdict.kit.cache.OfflineCache
import com.frederikberg.mailverdict.kit.live.ConnectionState
import com.frederikberg.mailverdict.kit.live.Reachability
import com.frederikberg.mailverdict.kit.reader.ApiReaderBackend
import com.frederikberg.mailverdict.kit.reader.MessagePlaceResolver
import com.frederikberg.mailverdict.kit.reader.ReaderResources
import com.frederikberg.mailverdict.kit.reader.ReaderStore
import com.frederikberg.mailverdict.kit.reader.RecentViewRecord
import com.frederikberg.mailverdict.kit.session.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The stores of the mail list and the reader for one connection. A list is kept per scope, so the
 * reader and back find the rows, the window and the position where they were; the reader per opened
 * message, so a rotation does not read the conversation again.
 */
class MailArea(
    private val connection: Connection,
    private val scope: CoroutineScope,
    val cache: OfflineCache,
    private val preferences: MailListPreferences,
    val recent: RecentViewRecord,
    actionsPersistence: IntentPersistence,
    val recentMoves: RecentMoveTargets,
) {
    private val backend = ApiMailListBackend(connection.api)
    private val lists = HashMap<String, MailListStore>()
    private val readers = HashMap<String, ReaderStore>()

    /** Every mail action of the connection, durable and delivered in order. Created with the connection so what the last run left unsent goes out without waiting for a screen. */
    val actions: ActionQueue =
        ActionQueue(
            ApiIntentTransport(connection.api),
            actionsPersistence,
            scope,
            object : QueueConnectivity {
                override val online: StateFlow<Boolean> =
                    connection.reachability.map { it != Reachability.Offline }.stateIn(
                        scope,
                        SharingStarted.Eagerly,
                        true,
                    )
            },
        )

    init {
        // The stream coming back (the app returning to the foreground included) is when a held credential is tried again.
        scope.launch {
            connection.liveEventHub.connectionState.collect {
                if (it ==
                    ConnectionState.Connected
                ) {
                    actions.resume()
                }
            }
        }
    }

    val resources = ReaderResources(connection.api, cache)

    val resolver =
        MessagePlaceResolver(
            locate = backend::locate,
            unifiedViews = backend::unifiedViews,
            recent = recent,
        )

    /** The list of [listScope]; [aroundMessageId] opens a window around that message instead of at the newest edge. */
    @Synchronized
    fun list(
        listScope: ListScope,
        aroundMessageId: String? = null,
    ): MailListStore =
        lists.getOrPut(listScope.key + "|" + aroundMessageId.orEmpty()) {
            MailListStore(
                listScope,
                backend,
                scope,
                preferences,
                cache,
                aroundMessageId = aroundMessageId,
                actions = actions,
            ).also {
                it.follow(
                    connection.liveEventHub.events,
                    connection.liveEventHub.pathRestored,
                    connection.liveEventHub.connectionState.map { state -> state == ConnectionState.Connected },
                )
            }
        }

    @Synchronized
    fun reader(
        openedMessageId: String,
        folderIds: Set<String>,
    ): ReaderStore =
        readers.getOrPut(openedMessageId) {
            ReaderStore(openedMessageId, folderIds, ApiReaderBackend(connection.api), cache)
        }

    /** The folders [accountId] offers a move to. */
    suspend fun moveTargets(
        accountId: String,
    ): List<MoveTarget> = MovePicker.targets(accountId, connection.api.getFolderOrder(accountId))
}
