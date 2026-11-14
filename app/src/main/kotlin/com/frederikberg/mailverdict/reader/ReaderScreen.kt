package com.frederikberg.mailverdict.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.actions.ActionMenuItems
import com.frederikberg.mailverdict.actions.CollectQueueNotices
import com.frederikberg.mailverdict.kit.actions.ActionGroup
import com.frederikberg.mailverdict.kit.actions.QueueNotice
import com.frederikberg.mailverdict.kit.actions.UiAction
import com.frederikberg.mailverdict.kit.live.OfflineNotice
import com.frederikberg.mailverdict.kit.reader.PlainBody
import com.frederikberg.mailverdict.kit.reader.PlainText
import com.frederikberg.mailverdict.kit.reader.ReaderAttachment
import com.frederikberg.mailverdict.kit.reader.ReaderBody
import com.frederikberg.mailverdict.kit.reader.ReaderDocument
import com.frederikberg.mailverdict.kit.reader.ReaderMessage
import com.frederikberg.mailverdict.kit.reader.ReaderPhase
import com.frederikberg.mailverdict.kit.reader.ReaderState
import com.frederikberg.mailverdict.ui.OfflineCapsule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

object ReaderTags {
    const val RETRY = "reader-retry"
    const val LIST = "reader-list"
    const val MENU = "reader-menu"

    fun message(id: String) = "reader-message:$id"

    fun toggle(id: String) = "reader-toggle:$id"

    fun showImages(id: String) = "reader-show-images:$id"

    fun showAll(id: String) = "reader-show-all:$id"

    fun attachment(id: String) = "reader-attachment:$id"

    fun body(id: String) = "reader-body:$id"
}

/**
 * A conversation, newest message first. Only the opened message starts expanded; every other is a
 * header of one fixed height, so nothing above the message being read changes size while it loads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderState,
    offline: OfflineNotice?,
    web: MessageWebContext,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggle: (String) -> Unit,
    onShowImages: (String) -> Unit,
    onShowFullBody: (String) -> Unit,
    onOpenAttachment: (ReaderMessage, ReaderAttachment) -> Unit,
    onDismissNotice: () -> Unit,
    actions: ReaderActions = ReaderActions.None,
) {
    val snackbar = remember { SnackbarHostState() }
    CollectQueueNotices(actions.notices, snackbar, actions.onUndo, actions.onRetryAction)
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            onDismissNotice()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    if (state.phase == ReaderPhase.Loaded && actions.groups.isNotEmpty()) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { open = true }, modifier = Modifier.testTag(ReaderTags.MENU)) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.maillist_more),
                                )
                            }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                ActionMenuItems(
                                    groups = actions.groups,
                                    onAction = {
                                        open = false
                                        actions.onAction(it)
                                    },
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val phase = state.phase) {
                ReaderPhase.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is ReaderPhase.Failed ->
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.reader_failed, phase.message),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRetry, modifier = Modifier.testTag(ReaderTags.RETRY)) {
                            Text(stringResource(R.string.reader_retry))
                        }
                    }
                ReaderPhase.Loaded ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(ReaderTags.LIST)) {
                        Text(
                            state.subject.ifEmpty { stringResource(R.string.reader_no_subject) },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = SUBJECT_LINES,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        state.messages.forEach { message ->
                            MessageCard(
                                message = message,
                                expanded = message.id in state.expandedIds,
                                fullBody = message.id in state.fullBodyIds,
                                web = web,
                                onToggle = { onToggle(message.id) },
                                onShowImages = { onShowImages(message.id) },
                                onShowFullBody = { onShowFullBody(message.id) },
                                onOpenAttachment = { onOpenAttachment(message, it) },
                            )
                        }
                    }
            }
            if (offline != null) OfflineCapsule(offline, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
    }
}

private const val SUBJECT_LINES = 4
private val COLLAPSED_HEIGHT = 64.dp

@Composable
private fun MessageCard(
    message: ReaderMessage,
    expanded: Boolean,
    fullBody: Boolean,
    web: MessageWebContext,
    onToggle: () -> Unit,
    onShowImages: () -> Unit,
    onShowFullBody: () -> Unit,
    onOpenAttachment: (ReaderAttachment) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag(ReaderTags.message(message.id))) {
        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (expanded) Modifier else Modifier.height(COLLAPSED_HEIGHT))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag(ReaderTags.toggle(message.id)),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.senderName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (message.isUnread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (expanded) message.fullDate else message.date,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (expanded) {
                    Text(
                        message.senderEmail,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (message.recipients.isNotEmpty()) {
                        Text(
                            message.recipients,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    message.cc?.let {
                        Text(
                            stringResource(R.string.reader_cc, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                } else {
                    Text(
                        message.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.reader_collapse else R.string.reader_expand),
            )
        }
        if (expanded) {
            Banners(message)
            message.attachments.forEach { AttachmentRow(it, onClick = { onOpenAttachment(it) }) }
            Body(message, fullBody, web, onShowImages, onShowFullBody)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Banners(message: ReaderMessage) {
    if (message.isDraft) Banner(stringResource(R.string.reader_draft), error = false)
    if (message.isSpam == true) Banner(stringResource(R.string.reader_spam), error = true)
    if (message.isGlacier) {
        Banner(
            message.glacierOrigin?.let { stringResource(R.string.reader_glacier_from, it) }
                ?: stringResource(R.string.reader_glacier),
            error = false,
        )
    }
}

@Composable
private fun Banner(
    text: String,
    error: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (error) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (error) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun AttachmentRow(
    attachment: ReaderAttachment,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(ReaderTags.attachment(attachment.id)),
    ) {
        Text("📎 ", style = MaterialTheme.typography.bodyMedium)
        Text(
            attachment.filename,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            " · ${attachment.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun Body(
    message: ReaderMessage,
    fullBody: Boolean,
    web: MessageWebContext,
    onShowImages: () -> Unit,
    onShowFullBody: () -> Unit,
) {
    when (val body = message.body) {
        is ReaderBody.Html -> {
            if (message.hasBlockedImages) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.reader_images_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onShowImages, modifier = Modifier.testTag(ReaderTags.showImages(message.id))) {
                        Text(stringResource(R.string.reader_show_images))
                    }
                }
            }
            val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            val page = remember(body.html, dark) { ReaderDocument.page(body.html, dark) }
            MessageWebView(
                page,
                remoteImages = message.imagesLoaded,
                context = web,
                modifier = Modifier.fillMaxWidth().testTag(ReaderTags.body(message.id)),
            )
        }
        is ReaderBody.Text -> {
            val shown = if (fullBody) remember(body.raw) { PlainText.prepare(body.raw, body.raw.length) } else body.body
            SelectionContainer {
                Text(
                    linked(shown),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(ReaderTags.body(message.id)),
                )
            }
            if (body.body.truncated && !fullBody) {
                Text(
                    stringResource(R.string.reader_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                TextButton(
                    onClick = onShowFullBody,
                    modifier = Modifier.padding(horizontal = 8.dp).testTag(ReaderTags.showAll(message.id)),
                ) {
                    Text(stringResource(R.string.reader_show_all))
                }
            }
        }
        ReaderBody.Empty ->
            Text(
                stringResource(R.string.reader_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
    }
}

@Composable
private fun linked(body: PlainBody): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return remember(body, color) {
        buildAnnotatedString {
            var at = 0
            for (link in body.links) {
                append(body.text.substring(at, link.start))
                withLink(
                    LinkAnnotation.Url(
                        link.url,
                        TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
                    ),
                ) {
                    append(body.text.substring(link.start, link.end))
                }
                at = link.end
            }
            append(body.text.substring(at))
        }
    }
}

/** What the reader needs to act on the opened message: the menu's entries, Undo and Retry. */
class ReaderActions(
    val notices: Flow<QueueNotice>,
    val groups: List<ActionGroup>,
    val onAction: (UiAction) -> Unit,
    val onUndo: (List<String>) -> Unit,
    val onRetryAction: (String) -> Unit,
) {
    companion object {
        val None = ReaderActions(emptyFlow(), emptyList(), {}, {}, {})
    }
}
