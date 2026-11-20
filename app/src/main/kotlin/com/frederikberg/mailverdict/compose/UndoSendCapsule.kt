package com.frederikberg.mailverdict.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.contract.PendingSendResponse
import com.frederikberg.mailverdict.kit.compose.ComposeArea
import com.frederikberg.mailverdict.kit.compose.ComposeIntent
import com.frederikberg.mailverdict.kit.compose.UndoOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object UndoSendTags {
    fun capsule(id: String) = "undo-send:$id"

    fun undo(id: String) = "undo-send-undo:$id"
}

/**
 * Over every screen of the signed-in app: one capsule per send still inside the server's undo window,
 * counting down with Undo, and the notices a closed composer leaves ("Draft saved").
 */
@Composable
fun BoxScope.ComposeOverlay(
    area: ComposeArea,
    accountNames: Map<String, String>,
    onRestore: (ComposeIntent) -> Unit,
) {
    val undo by area.undoSend.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(area) { area.undoSend.refresh() }
    LaunchedEffect(area) { area.notices.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(undo.pending.isNotEmpty()) {
        while (undo.pending.isNotEmpty()) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }

    Column(
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        area.undoSend.visibleRows(now).forEach { row ->
            UndoCapsule(
                row = row,
                seconds = area.undoSend.secondsRemaining(row, now),
                account = accountNames[row.accountId].takeIf { accountNames.size > 1 },
                cancelling = row.id in undo.cancelling,
                onUndo = {
                    scope.launch {
                        when (val outcome = area.undoSend.undo(row.id)) {
                            is UndoOutcome.Restored -> {
                                area.notify(resources.getString(R.string.compose_send_cancelled))
                                onRestore(outcome.intent)
                            }
                            UndoOutcome.TooLate -> area.notify(resources.getString(R.string.compose_too_late))
                            UndoOutcome.Ignored -> Unit
                        }
                    }
                },
            )
        }
        SnackbarHost(snackbar)
    }
}

@Composable
fun UndoCapsule(
    row: PendingSendResponse,
    seconds: Long,
    account: String?,
    cancelling: Boolean,
    onUndo: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 16.dp).testTag(UndoSendTags.capsule(row.id)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp)) {
            Text(
                stringResource(R.string.compose_sending_in, seconds.toInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (account != null) {
                Box(Modifier.padding(start = 8.dp)) {
                    Text(account, style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(
                onClick = onUndo,
                enabled = !cancelling,
                modifier = Modifier.testTag(UndoSendTags.undo(row.id)),
            ) {
                Text(stringResource(R.string.compose_undo), color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

private const val TICK_MS = 250L
