package com.frederikberg.mailverdict.compose

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.contract.PendingSendResponse
import com.frederikberg.mailverdict.kit.compose.ComposerPhase
import com.frederikberg.mailverdict.kit.compose.ComposerStore
import com.frederikberg.mailverdict.kit.compose.LeaveOutcome
import com.frederikberg.mailverdict.kit.compose.RecipientField
import com.frederikberg.mailverdict.kit.compose.RecipientSuggestions
import com.frederikberg.mailverdict.kit.compose.SubmitOutcome
import kotlinx.coroutines.launch

/** How a composer ended: what to tell the person, and a send to hand to the undo capsule. */
class ComposeClosing(
    val notice: String?,
    val pending: PendingSendResponse? = null,
)

/**
 * The composer screen driven by its store: submitting, leaving (which saves unsaved work as a
 * draft), discarding with a question first, and the suggestions under the field being typed in.
 */
@Composable
fun ComposeStoreScreen(
    store: ComposerStore,
    suggestions: RecipientSuggestions,
    onAttach: () -> Unit,
    onClosed: (ComposeClosing) -> Unit,
) {
    val state by store.state.collectAsState()
    val hits by suggestions.hits.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmingDiscard by remember { mutableStateOf(false) }
    var leaveError by remember { mutableStateOf<String?>(null) }
    var focusedField by remember { mutableStateOf<RecipientField?>(null) }
    val queued = stringResource(R.string.compose_queued)

    LaunchedEffect(store) { if (store.state.value.phase == ComposerPhase.Loading) store.load() }

    val editing =
        remember(store) {
            object : ComposeEditing {
                override fun subject() = store.state.value.subject

                override fun body() = store.state.value.body

                override fun recipientText(field: RecipientField) = store.recipientText(field)

                override fun onSubject(value: String) = store.setSubject(value)

                override fun onBody(value: String) = store.setBody(value)

                override fun onRecipientText(
                    field: RecipientField,
                    text: String,
                ) {
                    store.setRecipientText(field, text)
                    suggestions.query(store.recipientText(field))
                }

                override fun onCommit(field: RecipientField) {
                    store.commitRecipientText(field)
                    suggestions.clear()
                }

                override fun onPick(
                    field: RecipientField,
                    address: String,
                ) {
                    store.addRecipient(field, address)
                    suggestions.clear()
                }

                override fun onRemove(
                    field: RecipientField,
                    address: String,
                ) = store.removeRecipient(field, address)

                override fun onShowCcBcc() = store.showCcBcc()

                override fun onSelectFrom(key: String) = store.selectFrom(key)

                override fun onRemoveAttachment(id: String) = store.removeAttachment(id)

                override fun onRemoveQuote() = store.removeQuote()

                override fun onRestore() = store.restoreRecovered()

                override fun onDismissRestore() = store.dismissRecovered()
            }
        }

    fun submit(draft: Boolean) {
        scope.launch {
            when (val outcome = store.submit(draft)) {
                SubmitOutcome.Sent -> onClosed(ComposeClosing(queued))
                SubmitOutcome.DraftSaved -> onClosed(ComposeClosing(ComposerStore.DRAFT_SAVED))
                is SubmitOutcome.Pending -> onClosed(ComposeClosing(null, outcome.send))
                else -> Unit
            }
        }
    }

    fun leave() {
        scope.launch {
            when (val outcome = store.leave()) {
                is LeaveOutcome.Close -> onClosed(ComposeClosing(outcome.notice))
                is LeaveOutcome.Stay -> leaveError = outcome.message
            }
        }
    }

    BackHandler(enabled = state.phase != ComposerPhase.Sending) { leave() }

    ComposeScreen(
        state = state,
        editing = editing,
        suggestions = if (focusedField != null) hits else emptyList(),
        focusRecipients = store.intent.focusesRecipients,
        onFocusRecipientField = { focusedField = it },
        onSend = { submit(draft = false) },
        onSaveDraft = { submit(draft = true) },
        onAttach = onAttach,
        onDiscard = {
            if (state.isDirty) {
                confirmingDiscard = true
            } else {
                store.discard()
                onClosed(ComposeClosing(null))
            }
        },
        onClose = ::leave,
        onRetry = { scope.launch { store.load() } },
    )

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.compose_discard_title)) },
            text = { Text(stringResource(R.string.compose_discard_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDiscard = false
                    store.discard()
                    onClosed(ComposeClosing(null))
                }) { Text(stringResource(R.string.compose_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingDiscard = false },
                ) { Text(stringResource(R.string.compose_keep_editing)) }
            },
        )
    }
    leaveError?.let { message ->
        AlertDialog(
            onDismissRequest = { leaveError = null },
            title = { Text(stringResource(R.string.compose_not_saved_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { leaveError = null }) { Text(stringResource(R.string.compose_keep_editing)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    leaveError = null
                    store.discard()
                    onClosed(ComposeClosing(null))
                }) { Text(stringResource(R.string.compose_discard), color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}
