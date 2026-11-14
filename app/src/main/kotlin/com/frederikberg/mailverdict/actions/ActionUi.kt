package com.frederikberg.mailverdict.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.kit.actions.ActionGroup
import com.frederikberg.mailverdict.kit.actions.MovePicker
import com.frederikberg.mailverdict.kit.actions.MoveTarget
import com.frederikberg.mailverdict.kit.actions.QueueNotice
import com.frederikberg.mailverdict.kit.actions.UiAction
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

object ActionTags {
    const val MOVE_FIELD = "actions-move-field"
    const val CONFIRM_FOREVER = "actions-confirm-forever"
    const val CANCEL_FOREVER = "actions-cancel-forever"

    fun menu(action: UiAction) = "action:${action.name}"

    fun target(folderId: String) = "move-target:$folderId"
}

/** The menu entries of [groups], one list for every surface that shows actions. Call inside a `DropdownMenu`. */
@Composable
fun ActionMenuItems(
    groups: List<ActionGroup>,
    onAction: (UiAction) -> Unit,
    skip: Set<UiAction> = emptySet(),
) {
    val actions = groups.map { group -> group.actions.filter { it !in skip } }.filter { it.isNotEmpty() }
    actions.forEachIndexed { index, group ->
        if (index > 0) HorizontalDivider()
        group.forEach { action ->
            DropdownMenuItem(
                text = {
                    Text(
                        action.title,
                        color =
                            if (action.isDestructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                },
                onClick = { onAction(action) },
                modifier = Modifier.testTag(ActionTags.menu(action)),
            )
        }
    }
}

/** Permanent deletion asks first: there is no undo. */
@Composable
fun ConfirmDeleteForeverDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.actions_delete_forever_title)) },
        text = { Text(pluralStringResource(R.plurals.actions_delete_forever_text, count, count)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(ActionTags.CONFIRM_FOREVER)) {
                Text(stringResource(R.string.actions_delete_forever_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ActionTags.CANCEL_FOREVER)) {
                Text(stringResource(R.string.actions_cancel))
            }
        },
    )
}

/** The folders a move can go to, most recent first, filtered as the person types; [targets] null while they load. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovePickerSheet(
    targets: List<MoveTarget>?,
    excludingFolderIds: Set<String>,
    recentIds: List<String>,
    onPick: (MoveTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.actions_move_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.actions_move_search)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(ActionTags.MOVE_FIELD),
            )
            if (targets == null) {
                Text(stringResource(R.string.actions_move_loading), modifier = Modifier.padding(vertical = 16.dp))
            } else {
                val shown =
                    remember(targets, excludingFolderIds, recentIds, query) {
                        MovePicker.ordered(targets, excludingFolderIds, recentIds, query)
                    }
                LazyColumn {
                    items(shown, key = { it.folderId }) { target ->
                        Text(
                            target.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPick(target)
                                    }.padding(vertical = 14.dp)
                                    .testTag(ActionTags.target(target.folderId)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shows what the queue tells the person: an Undo offer for something just done, a failure with Retry,
 * a plain remark. Each is one snackbar; a newer one replaces the one on screen.
 */
@Composable
fun CollectQueueNotices(
    notices: Flow<QueueNotice>,
    snackbar: SnackbarHostState,
    onUndo: (List<String>) -> Unit,
    onRetry: (String) -> Unit,
) {
    val undo by rememberUpdatedState(onUndo)
    val retry by rememberUpdatedState(onRetry)
    LaunchedEffect(notices) {
        notices.collect { notice ->
            snackbar.currentSnackbarData?.dismiss()
            // Collected on its own: showing a snackbar suspends until it goes, and the next notice must not wait behind it.
            coroutineScope {
                launch {
                    val result =
                        when (notice) {
                            is QueueNotice.Undo ->
                                snackbar.showSnackbar(
                                    notice.message,
                                    ACTION_UNDO,
                                    duration = SnackbarDuration.Long,
                                )
                            is QueueNotice.Failure ->
                                snackbar.showSnackbar(
                                    notice.message,
                                    ACTION_RETRY,
                                    duration = SnackbarDuration.Indefinite,
                                )
                            is QueueNotice.Info -> snackbar.showSnackbar(notice.message)
                        }
                    if (result == SnackbarResult.ActionPerformed) {
                        when (notice) {
                            is QueueNotice.Undo -> undo(notice.ids)
                            is QueueNotice.Failure -> retry(notice.id)
                            is QueueNotice.Info -> Unit
                        }
                    }
                }
            }
        }
    }
}

const val ACTION_UNDO = "Undo"
const val ACTION_RETRY = "Retry"
