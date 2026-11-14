package com.frederikberg.mailverdict.push

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.frederikberg.mailverdict.AppGraph
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.UnifiedPush

private const val DISTRIBUTORS_URL = "https://unifiedpush.org/users/distributors/"

/**
 * The settings screen wired to the controller. Choosing a distributor is a system dialog that needs an
 * activity, so that step lives here and hands back to the controller once the choice is saved.
 */
@Composable
fun PushSettingsRoute(
    graph: AppGraph,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifier = remember { PushNotifier(context.applicationContext) }
    val status by graph.push.status.collectAsState()
    val notice by graph.push.notice.collectAsState()
    var systemAllowed by remember { mutableStateOf(notifier.isAllowed()) }

    LaunchedEffect(Unit) { graph.push.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { systemAllowed = notifier.isAllowed() }

    var enableAfterPermission by remember { mutableStateOf(false) }

    // Picks the saved distributor, else the system default, which asks the person to choose when there are several.
    fun chooseDistributorAndEnable() {
        val activity = context as? Activity
        if (activity == null) {
            scope.launch { graph.push.enable() }
        } else {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { chosen ->
                scope.launch { if (chosen) graph.push.enable() else graph.push.refresh() }
            }
        }
    }

    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            systemAllowed = notifier.isAllowed()
            if (enableAfterPermission) {
                enableAfterPermission = false
                chooseDistributorAndEnable()
            }
        }

    fun requestSystemPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notifier.needsPermissionRequest()) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }

    PushSettingsScreen(
        status = status,
        systemAllowed = systemAllowed,
        notice = notice,
        callbacks =
            PushSettingsCallbacks(
                onBack = onBack,
                onEnable = {
                    if (notifier.needsPermissionRequest()) {
                        enableAfterPermission = true
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        chooseDistributorAndEnable()
                    }
                },
                onDisable = { scope.launch { graph.push.disable() } },
                onSendTest = { scope.launch { graph.push.sendTest() } },
                onGetDistributor = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DISTRIBUTORS_URL)))
                },
                onChooseDistributor = {
                    (context as? Activity)?.let { activity ->
                        UnifiedPush.tryPickDistributor(activity) { chosen ->
                            scope.launch { if (chosen) graph.push.enable() }
                        }
                    }
                },
                onAllowSystem = ::requestSystemPermission,
                onMuted = { channel, muted -> scope.launch { graph.push.setMuted(channel, muted) } },
            ),
    )
}
