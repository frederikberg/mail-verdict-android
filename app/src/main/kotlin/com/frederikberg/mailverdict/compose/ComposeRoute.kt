package com.frederikberg.mailverdict.compose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.frederikberg.mailverdict.AppGraph
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.kit.compose.ComposeAttachment
import com.frederikberg.mailverdict.kit.compose.ComposeIntent
import com.frederikberg.mailverdict.kit.session.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The composer for [intent], kept in the connection's compose area so a rotation keeps what was typed. */
@Composable
fun ComposeRoute(
    graph: AppGraph,
    connection: Connection,
    intent: ComposeIntent,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val area = remember(connection) { graph.composeArea(connection) }
    val store = remember(area, intent) { area.composer(intent) }
    val scope = rememberCoroutineScope()
    val suggestions = remember(area, scope) { area.suggestions(scope) }

    // The system picker hands back documents; they are read whole, which is what the upload sends.
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            scope.launch {
                val read =
                    withContext(Dispatchers.IO) {
                        uris.map { uri ->
                            uri to
                                runCatching { readDocument(context, uri) }.getOrNull()
                        }
                    }
                store.addAttachments(read.mapNotNull { it.second })
                read.filter { it.second == null }.forEach { (uri, _) ->
                    area.notify(resources.getString(R.string.compose_attachment_failed, uri.lastPathSegment.orEmpty()))
                }
            }
        }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { store.saveRecoverySnapshot() }

    ComposeStoreScreen(
        store = store,
        suggestions = suggestions,
        onAttach = { picker.launch(arrayOf("*/*")) },
        onClosed = { closing ->
            closing.pending?.let(area.undoSend::add)
            closing.notice?.let(area::notify)
            area.close(intent)
            onClose()
        },
    )
}

private fun readDocument(
    context: Context,
    uri: Uri,
): ComposeAttachment {
    val resolver = context.contentResolver
    val name =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "attachment"
    val data = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("unreadable")
    return ComposeAttachment(name, resolver.getType(uri), data)
}
