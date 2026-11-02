package io.github.markhunter123.muse.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.markhunter123.muse.core.trigger.TriggerEvents

/** Notification reading (whitelisted apps) + notification triggers + media session access. */
class DshNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        TriggerEvents.notificationPosted(
            sbn.packageName,
            extras.getCharSequence("android.title")?.toString() ?: "",
            (extras.getCharSequence("android.bigText") ?: extras.getCharSequence("android.text"))?.toString() ?: "",
        )
    }

    companion object {
        @Volatile var instance: DshNotificationListener? = null
            private set
    }
}
