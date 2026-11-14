package com.frederikberg.mailverdict.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.FileProvider
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.actions.ConfirmDeleteForeverDialog
import com.frederikberg.mailverdict.actions.MovePickerSheet
import com.frederikberg.mailverdict.kit.actions.MessageActionSet
import com.frederikberg.mailverdict.kit.actions.MoveTarget
import com.frederikberg.mailverdict.kit.actions.UiAction
import com.frederikberg.mailverdict.kit.api.getAttachment
import com.frederikberg.mailverdict.kit.live.OfflineNotice
import com.frederikberg.mailverdict.kit.net.ApiError
import com.frederikberg.mailverdict.kit.reader.ReaderAttachment
import com.frederikberg.mailverdict.kit.reader.ReaderMessage
import com.frederikberg.mailverdict.kit.reader.ReaderNavigation
import com.frederikberg.mailverdict.kit.session.Connection
import com.frederikberg.mailverdict.maillist.mailArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The reader wired to its store; opening a conversation marks it read. */
@Composable
fun ReaderRoute(
    graph: AppGraph,
    connection: Connection,
    messageId: String,
    folderIds: Set<String>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val area = remember(connection) { graph.mailArea(connection) }
    val store = remember(area, messageId) { area.reader(messageId, folderIds) }
    val state by store.state.collectAsState()
    val reachability by connection.reachability.collectAsState()
    val scope = rememberCoroutineScope()

    val queue by area.actions.state.collectAsState()
    var moving by remember { mutableStateOf(false) }
    var moveTargets by remember { mutableStateOf<List<MoveTarget>?>(null) }
    var confirmForever by remember { mutableStateOf(false) }

    LaunchedEffect(store) { store.load() }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        moveTargets = null
        moveTargets =
            store.accountOf(messageId)?.let { runCatching { area.moveTargets(it) }.getOrDefault(emptyList()) }.orEmpty()
    }

    // An action that takes the message out of its folder leaves the reader with nothing to show.
    fun run(action: UiAction) {
        when (action) {
            UiAction.MoveTo -> moving = true
            UiAction.DeleteForever -> confirmForever = true
            UiAction.ConfirmVerdict, UiAction.CorrectVerdict ->
                scope.launch { store.sendVerdictFeedback(messageId, confirming = action == UiAction.ConfirmVerdict) }
            else -> if (store.perform(action, messageId)) onBack()
        }
    }

    val web =
        remember(connection, area) {
            MessageWebContext(
                serverUrl = connection.serverUrl,
                loadResource = area.resources::load,
                onNavigate = { open(context, it) },
            )
        }

    ReaderScreen(
        state = state,
        offline = OfflineNotice.make(reachability, state.savedCopyAtMs),
        web = web,
        onBack = onBack,
        onRetry = { scope.launch { store.load() } },
        onToggle = store::toggleExpanded,
        onShowImages = { id -> scope.launch { store.loadImages(id) } },
        onShowFullBody = store::showFullBody,
        onOpenAttachment = { message, attachment ->
            scope.launch {
                runCatching { openAttachment(context, graph, connection, message, attachment) }
                    .onFailure {
                        store.reportNotice(
                            when (it) {
                                is ActivityNotFoundException ->
                                    resources.getString(
                                        R.string.reader_no_app_for_attachment,
                                    )
                                is ApiError -> resources.getString(R.string.reader_attachment_failed, it.userMessage)
                                else -> resources.getString(R.string.reader_attachment_failed, it.message.orEmpty())
                            },
                        )
                    }
            }
        },
        onDismissNotice = store::dismissNotice,
        actions =
            ReaderActions(
                notices = area.actions.notices,
                // Reading the state of the queue is what makes the menu follow an action just taken (Mark as unread turning into Mark as read).
                groups = queue.let { store.actionContext(messageId)?.let(MessageActionSet::groups).orEmpty() },
                onAction = ::run,
                onUndo = area.actions::undo,
                onRetryAction = { area.actions.retry(listOf(it)) },
            ),
    )

    if (moving) {
        val account = store.accountOf(messageId)
        MovePickerSheet(
            targets = moveTargets,
            excludingFolderIds = setOfNotNull(store.folderOf(messageId)),
            recentIds = account?.let(area.recentMoves::recentIds).orEmpty(),
            onPick = { target ->
                area.recentMoves.record(target.accountId, target.folderId)
                moving = false
                if (store.perform(UiAction.MoveTo, messageId, target.folderId)) onBack()
            },
            onDismiss = { moving = false },
        )
    }
    if (confirmForever) {
        ConfirmDeleteForeverDialog(
            count = 1,
            onConfirm = {
                confirmForever = false
                if (store.perform(UiAction.DeleteForever, messageId)) onBack()
            },
            onDismiss = { confirmForever = false },
        )
    }
}

/** A link tapped in a message leaves the app; the page itself never navigates. */
private fun open(
    context: Context,
    navigation: ReaderNavigation,
) {
    val intent =
        when (navigation) {
            is ReaderNavigation.Web -> Intent(Intent.ACTION_VIEW, Uri.parse(navigation.url))
            is ReaderNavigation.Mailto -> Intent(Intent.ACTION_SENDTO, Uri.parse(navigation.url))
            is ReaderNavigation.Phone -> Intent(Intent.ACTION_DIAL, Uri.parse(navigation.url))
            is ReaderNavigation.Anchor, ReaderNavigation.Ignore -> return
        }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No app handles this kind of link; nothing to do.
    }
}

/** Downloads the attachment through the authenticated client into the cache and hands it to whichever app opens that type. */
private suspend fun openAttachment(
    context: Context,
    graph: AppGraph,
    connection: Connection,
    message: ReaderMessage,
    attachment: ReaderAttachment,
) {
    val raw = connection.api.getAttachment(message.id, attachment.id)
    val file =
        withContext(Dispatchers.IO) {
            File(
                graph.mailCacheRoot,
                "attachments/${message.id}-${attachment.id}/${safeFileName(attachment.filename)}",
            ).also {
                it.parentFile?.mkdirs()
                it.writeBytes(raw.data)
            }
        }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, attachment.contentType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun safeFileName(name: String): String =
    name
        .replace(
            Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"),
            "_",
        ).take(120)
        .ifBlank {
            "attachment"
        }
