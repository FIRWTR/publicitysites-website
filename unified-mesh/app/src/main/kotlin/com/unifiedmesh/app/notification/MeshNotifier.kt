package com.unifiedmesh.app.notification

import android.Manifest
import android.app.Notification
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
import com.unifiedmesh.app.MainActivity
import com.unifiedmesh.app.R
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.core.model.UnifiedMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * The app's notifications: one ongoing status notification for the foreground
 * service, and one per incoming message.
 *
 * Message notifications always name the network they arrived on. In the field
 * that is the difference between replying on the radio that can actually reach
 * the sender and replying into the void.
 */
@Singleton
class MeshNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val status = NotificationChannel(
            STATUS_CHANNEL_ID,
            context.getString(R.string.notification_channel_status),
            // LOW: the ongoing notification is a status line, not an alert. It
            // must never buzz.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_status_description)
            setShowBadge(false)
        }

        val messages = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_messages_description)
            enableVibration(true)
        }

        manager.createNotificationChannels(listOf(status, messages))
    }

    /**
     * The ongoing service notification.
     *
     * It reports both radios on their own lines, so a glance at the shade
     * answers "are both still up?" without opening the app.
     */
    fun statusNotification(states: Map<MeshProtocol, RadioConnectionState>): Notification {
        val lines = MeshProtocol.entries.map { protocol ->
            "${protocol.displayName}: ${describe(states[protocol])}"
        }
        val summary = lines.joinToString("  ")

        return NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .build()
    }

    fun updateStatus(states: Map<MeshProtocol, RadioConnectionState>) {
        if (!canPostNotifications()) return
        manager.notify(STATUS_NOTIFICATION_ID, statusNotification(states))
    }

    /** Raises a notification for one received message. */
    fun notifyMessage(message: UnifiedMessage, conversationTitle: String?) {
        if (!canPostNotifications()) return

        val sender = message.senderName ?: message.senderId
        // "Meshtastic - Bear" rather than just "Bear": which network a message
        // came in on is the first thing the operator needs.
        val title = "${message.protocol.displayName} - $sender"
        val subText = conversationTitle?.takeIf { it != sender }

        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .apply { subText?.let { setSubText(it) } }
            .setWhen(message.timestamp)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openConversationIntent(message.conversationId))
            // Group per conversation so a busy channel collapses into one thread
            // rather than burying the shade.
            .setGroup(message.conversationId)
            .build()

        manager.notify(notificationIdFor(message), notification)
    }

    fun cancelConversation(conversationId: String) {
        manager.cancel(conversationId.hashCode().absoluteValue)
    }

    /**
     * True when the app may post notifications.
     *
     * From API 33 this is a runtime permission the operator can decline; the
     * radios keep working either way, so this returns false rather than throwing.
     */
    private fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun describe(state: RadioConnectionState?): String = when (state) {
        null, RadioConnectionState.Disconnected -> context.getString(R.string.state_disconnected)
        RadioConnectionState.Connecting -> context.getString(R.string.state_connecting)
        RadioConnectionState.Handshaking -> context.getString(R.string.state_handshaking)
        is RadioConnectionState.Connected -> context.getString(R.string.state_connected)
        is RadioConnectionState.Reconnecting -> context.getString(R.string.state_reconnecting)
        is RadioConnectionState.Error -> context.getString(R.string.state_error)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openConversationIntent(conversationId: String): PendingIntent = PendingIntent.getActivity(
        context,
        conversationId.hashCode(),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * One notification slot per conversation.
     *
     * Keyed on the conversation rather than the message so a burst on one channel
     * replaces itself instead of stacking twenty entries.
     */
    private fun notificationIdFor(message: UnifiedMessage): Int =
        message.conversationId.hashCode().absoluteValue

    companion object {
        const val STATUS_CHANNEL_ID = "radio_status"
        const val MESSAGE_CHANNEL_ID = "messages"
        const val STATUS_NOTIFICATION_ID = 1
    }
}
