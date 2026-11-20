package com.frederikberg.mailverdict.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.contract.ContactSearchHitOut
import com.frederikberg.mailverdict.kit.compose.ComposeAttachment
import com.frederikberg.mailverdict.kit.compose.ComposerPhase
import com.frederikberg.mailverdict.kit.compose.ComposerState
import com.frederikberg.mailverdict.kit.compose.RecipientField
import com.frederikberg.mailverdict.kit.format.MailFormat

object ComposeTags {
    const val CLOSE = "compose-close"
    const val SEND = "compose-send"
    const val ATTACH = "compose-attach"
    const val MENU = "compose-menu"
    const val SAVE_DRAFT = "compose-save-draft"
    const val REMOVE_QUOTE = "compose-remove-quote"
    const val DISCARD = "compose-discard"
    const val FROM = "compose-from"
    const val CC_BCC = "compose-cc-bcc"
    const val SUBJECT = "compose-subject"
    const val BODY = "compose-body"
    const val QUOTE_TOGGLE = "compose-quote-toggle"
    const val SENDING = "compose-sending"
    const val RESTORE = "compose-restore"
    const val RETRY = "compose-retry"
    const val ERROR = "compose-error"

    fun field(field: RecipientField) = "compose-field:${field.name}"

    fun chip(
        field: RecipientField,
        address: String,
    ) = "compose-chip:${field.name}:$address"

    fun suggestion(email: String) = "compose-suggestion:$email"

    fun from(key: String) = "compose-from:$key"

    fun attachment(id: String) = "compose-attachment:$id"
}

/**
 * What the screen writes and reads back. The text fields stay authoritative while focused: every
 * change is written out at once, and the stored text is read in only when a revision says the store
 * replaced it.
 */
interface ComposeEditing {
    fun subject(): String

    fun body(): String

    fun recipientText(field: RecipientField): String

    fun onSubject(value: String)

    fun onBody(value: String)

    fun onRecipientText(
        field: RecipientField,
        text: String,
    )

    fun onCommit(field: RecipientField)

    fun onPick(
        field: RecipientField,
        address: String,
    )

    fun onRemove(
        field: RecipientField,
        address: String,
    )

    fun onShowCcBcc()

    fun onSelectFrom(key: String)

    fun onRemoveAttachment(id: String)

    fun onRemoveQuote()

    fun onRestore()

    fun onDismissRestore()
}

/** The composer: From, recipients, subject, the body, the quoted original and the files, with Send and the rest of what a message can do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    state: ComposerState,
    editing: ComposeEditing,
    suggestions: List<ContactSearchHitOut>,
    focusRecipients: Boolean,
    onFocusRecipientField: (RecipientField?) -> Unit,
    onSend: () -> Unit,
    onSaveDraft: () -> Unit,
    onAttach: () -> Unit,
    onDiscard: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag(ComposeTags.CLOSE)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.compose_close))
                    }
                },
                actions = {
                    if (state.phase == ComposerPhase.Editing) {
                        IconButton(onClick = onAttach, modifier = Modifier.testTag(ComposeTags.ATTACH)) {
                            Text("📎", style = MaterialTheme.typography.titleMedium)
                        }
                        IconButton(onClick = onSend, modifier = Modifier.testTag(ComposeTags.SEND)) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.compose_send),
                            )
                        }
                        Overflow(state, editing, onSaveDraft, onDiscard)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (val phase = state.phase) {
                ComposerPhase.Loading ->
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.compose_loading), modifier = Modifier.padding(top = 12.dp))
                    }
                is ComposerPhase.LoadFailed ->
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.compose_load_failed, phase.message),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRetry, modifier = Modifier.testTag(ComposeTags.RETRY)) {
                            Text(stringResource(R.string.compose_retry))
                        }
                    }
                ComposerPhase.Editing, ComposerPhase.Sending -> {
                    Form(state, editing, suggestions, focusRecipients, onFocusRecipientField)
                    if (phase == ComposerPhase.Sending) Sending()
                }
            }
        }
    }
}

@Composable
private fun Overflow(
    state: ComposerState,
    editing: ComposeEditing,
    onSaveDraft: () -> Unit,
    onDiscard: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(ComposeTags.MENU)) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.maillist_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.compose_save_draft)) },
                onClick = {
                    open = false
                    onSaveDraft()
                },
                modifier = Modifier.testTag(ComposeTags.SAVE_DRAFT),
            )
            if (state.quote != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.compose_remove_quote)) },
                    onClick = {
                        open = false
                        editing.onRemoveQuote()
                    },
                    modifier = Modifier.testTag(ComposeTags.REMOVE_QUOTE),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.compose_discard), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onDiscard()
                },
                modifier = Modifier.testTag(ComposeTags.DISCARD),
            )
        }
    }
}

@Composable
private fun Form(
    state: ComposerState,
    editing: ComposeEditing,
    suggestions: List<ContactSearchHitOut>,
    focusRecipients: Boolean,
    onFocusRecipientField: (RecipientField?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf<RecipientField?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (state.unrestoredAttachments.isNotEmpty()) {
            Notice(
                stringResource(R.string.compose_unrestored, state.unrestoredAttachments.joinToString(", ")),
                error = true,
            )
        }
        if (state.recoverable) RecoveryBanner(editing)
        if (state.showsFromPicker) FromRow(state, editing)
        val fields = if (state.showsCcBcc) RecipientField.entries else listOf(RecipientField.To)
        fields.forEach { field ->
            RecipientRow(
                field = field,
                state = state,
                editing = editing,
                requestFocus = focusRecipients && field == RecipientField.To,
                onFocus = { has ->
                    if (has) {
                        focused = field
                        onFocusRecipientField(field)
                    } else if (focused == field) {
                        focused = null
                        onFocusRecipientField(null)
                        editing.onCommit(field)
                    }
                },
                trailing = {
                    if (field == RecipientField.To && !state.showsCcBcc) {
                        TextButton(onClick = editing::onShowCcBcc, modifier = Modifier.testTag(ComposeTags.CC_BCC)) {
                            Text(stringResource(R.string.compose_cc_bcc))
                        }
                    }
                },
            )
            state.recipientNotes[field]?.let { Notice(it, error = true) }
            if (field == focused && suggestions.isNotEmpty()) Suggestions(suggestions) { editing.onPick(field, it) }
            if (field == RecipientField.To) state.sendHint?.let { Notice(it, error = true) }
            HorizontalDivider()
        }
        SubjectRow(state, editing)
        HorizontalDivider()
        BodyField(state, editing, focus = !focusRecipients)
        state.quote?.let { quote -> QuoteCard(quote.attribution, state.quotePreview) }
        if (state.attachments.isNotEmpty()) Attachments(state.attachments, editing)
        state.submitError?.let {
            Text(
                it,
                color = colors.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp).testTag(ComposeTags.ERROR),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Notice(
    text: String,
    error: Boolean,
) {
    Text(
        text,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun RecoveryBanner(editing: ComposeEditing) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(
                stringResource(R.string.compose_recovered),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = editing::onDismissRestore) { Text(stringResource(R.string.compose_dismiss)) }
            TextButton(onClick = editing::onRestore, modifier = Modifier.testTag(ComposeTags.RESTORE)) {
                Text(stringResource(R.string.compose_restore))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(LABEL_WIDTH),
    )
}

@Composable
private fun FromRow(
    state: ComposerState,
    editing: ComposeEditing,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Label(stringResource(R.string.compose_from))
        Box(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { open = true }
                        .padding(
                            vertical = 10.dp,
                        ).testTag(ComposeTags.FROM),
            ) {
                Text(
                    state.selectedFrom?.label.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.fromOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            open = false
                            editing.onSelectFrom(option.key)
                        },
                        modifier = Modifier.testTag(ComposeTags.from(option.key)),
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientRow(
    field: RecipientField,
    state: ComposerState,
    editing: ComposeEditing,
    requestFocus: Boolean,
    onFocus: (Boolean) -> Unit,
    trailing: @Composable () -> Unit,
) {
    var value by remember(field) { mutableStateOf(TextFieldValue(editing.recipientText(field))) }
    LaunchedEffect(field, state.recipientRevision) {
        val stored = editing.recipientText(field)
        if (value.text != stored) value = TextFieldValue(stored, TextRange(stored.length))
    }
    val focus = remember { FocusRequester() }
    if (requestFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Box(Modifier.padding(top = 12.dp)) { Label(field.label) }
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            state.recipients(field).forEach { address ->
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text(address, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.compose_remove, address),
                            modifier = Modifier.size(18.dp).clickable { editing.onRemove(field, address) },
                        )
                    },
                    modifier = Modifier.widthIn(max = 280.dp).testTag(ComposeTags.chip(field, address)),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    editing.onRecipientText(field, it.text)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                keyboardActions = KeyboardActions(onNext = { editing.onCommit(field) }),
                modifier =
                    Modifier
                        .widthIn(min = 140.dp)
                        .padding(vertical = 12.dp)
                        .focusRequester(focus)
                        .onFocusChanged { onFocus(it.isFocused) }
                        .testTag(ComposeTags.field(field)),
            )
        }
        trailing()
    }
}

@Composable
private fun Suggestions(
    hits: List<ContactSearchHitOut>,
    onPick: (String) -> Unit,
) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column {
            hits.forEach { hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(hit.email) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag(ComposeTags.suggestion(hit.email)),
                ) {
                    Text(hit.name.ifBlank { hit.email }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    if (hit.name.isNotBlank()) {
                        Text(
                            hit.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubjectRow(
    state: ComposerState,
    editing: ComposeEditing,
) {
    var value by remember { mutableStateOf(TextFieldValue(editing.subject())) }
    LaunchedEffect(state.textRevision) {
        val stored = editing.subject()
        if (value.text != stored) value = TextFieldValue(stored, TextRange(stored.length))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Label(stringResource(R.string.compose_subject))
        BasicTextField(
            value = value,
            onValueChange = {
                value = it
                editing.onSubject(it.text)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp).testTag(ComposeTags.SUBJECT),
        )
    }
}

@Composable
private fun BodyField(
    state: ComposerState,
    editing: ComposeEditing,
    focus: Boolean,
) {
    var value by remember { mutableStateOf(TextFieldValue(editing.body(), TextRange(0))) }
    LaunchedEffect(state.textRevision) {
        val stored = editing.body()
        if (value.text != stored) value = TextFieldValue(stored, TextRange(0))
    }
    val requester = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    Box(Modifier.fillMaxWidth().padding(16.dp)) {
        if (value.text.isEmpty()) {
            Text(
                stringResource(R.string.compose_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = {
                value = it
                editing.onBody(it.text)
            },
            minLines = BODY_MIN_LINES,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().focusRequester(requester).testTag(ComposeTags.BODY),
        )
    }
}

@Composable
private fun QuoteCard(
    attribution: String,
    preview: String,
) {
    var shown by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                attribution,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { shown = !shown }, modifier = Modifier.testTag(ComposeTags.QUOTE_TOGGLE)) {
                Text(stringResource(if (shown) R.string.compose_hide_quote else R.string.compose_show_quote))
            }
            if (shown) {
                Text(
                    preview.take(QUOTE_PREVIEW_CHARS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Attachments(
    attachments: List<ComposeAttachment>,
    editing: ComposeEditing,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        attachments.forEach { file ->
            InputChip(
                selected = false,
                onClick = {},
                label = {
                    Text(
                        "📎 ${file.filename} · ${MailFormat.size(file.sizeBytes.toLong())}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.compose_remove, file.filename),
                        modifier = Modifier.size(18.dp).clickable { editing.onRemoveAttachment(file.id) },
                    )
                },
                modifier = Modifier.widthIn(max = 320.dp).testTag(ComposeTags.attachment(file.id)),
            )
        }
    }
}

/** Covers the form while a send is on its way, so nothing is left to press twice; the form underneath comes back if it fails. */
@Composable
private fun Sending() {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().testTag(ComposeTags.SENDING)) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.compose_sending), modifier = Modifier.padding(start = 12.dp))
        }
    }
}

private val LABEL_WIDTH = 64.dp
private const val BODY_MIN_LINES = 8
private const val QUOTE_PREVIEW_CHARS = 4_000
