package com.unifiedmesh.app.notification

import com.unifiedmesh.core.database.SettingsRepository
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.UnifiedMessage
import com.unifiedmesh.core.radio.IncomingMessageListener
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raises a notification for each inbound message, when the operator wants them.
 *
 * Bridged copies are deliberately not notified: the operator already saw the
 * original when it arrived on the first network, and notifying both halves would
 * double every alert the moment the bridge is switched on.
 */
@Singleton
class IncomingMessageNotifier @Inject constructor(
    private val notifier: MeshNotifier,
    private val settings: SettingsRepository,
) : IncomingMessageListener {

    override suspend fun onMessage(message: UnifiedMessage) {
        if (message.direction != MessageDirection.INCOMING) return
        if (message.bridged) return
        if (!settings.general.first().notificationsEnabled) return
        notifier.notifyMessage(message, conversationTitle = null)
    }
}
