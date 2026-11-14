package com.frederikberg.mailverdict.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.frederikberg.mailverdict.MainActivity
import com.frederikberg.mailverdict.R
import com.frederikberg.mailverdict.contract.PushChannel
import com.frederikberg.mailverdict.kit.push.PushPayload
import com.frederikberg.mailverdict.kit.push.PushTarget

/** Turns a push payload into a system notification, and a tap on it back into a [PushTarget]. */
class PushNotifier(
    private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(
                channelId(PushChannel.Mail),
                context.getString(R.string.push_channel_mail),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        system.createNotificationChannel(
            NotificationChannel(
                channelId(PushChannel.System),
                context.getString(R.string.push_channel_system),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** False when the person (or Android 13's default) has not allowed this app to post notifications. */
    fun isAllowed(): Boolean = manager.areNotificationsEnabled()

    /** Whether the runtime permission still has to be asked for: Android 13 and later, not yet granted. */
    fun needsPermissionRequest(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    fun show(payload: PushPayload) {
        if (!isAllowed()) return
        createChannels()
        val notification =
            NotificationCompat
                .Builder(context, channelId(payload.channel))
                .setSmallIcon(R.drawable.ic_stat_mail)
                .setContentTitle(payload.title ?: context.getString(R.string.app_name))
                .setContentText(payload.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
                .setAutoCancel(true)
                .setContentIntent(tapIntent(payload))
                .build()
        // The tag is the alert id, so a second push for the same alert replaces the first.
        try {
            manager.notify(payload.tag ?: payload.url, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The permission was withdrawn between the check above and now.
        }
    }

    /** Withdraws the notification of an alert that was read in the app. */
    fun cancel(alertId: String) = manager.cancel(alertId, NOTIFICATION_ID)

    private fun tapIntent(payload: PushPayload): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ).putExtra(EXTRA_MESSAGE_ID, payload.messageId)
        val code = (payload.tag ?: payload.url ?: "").hashCode()
        return PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_OPEN = "com.frederikberg.mailverdict.OPEN_NOTIFICATION"
        const val EXTRA_MESSAGE_ID = "message_id"
        private const val NOTIFICATION_ID = 1

        fun channelId(channel: PushChannel) = "alerts-${channel.raw}"

        /** Where a launch intent from a notification leads; null for any other launch. */
        fun targetOf(intent: Intent?): PushTarget? {
            if (intent?.action != ACTION_OPEN) return null
            return intent.getStringExtra(EXTRA_MESSAGE_ID)?.let { PushTarget.Message(it) } ?: PushTarget.Alerts
        }
    }
}
