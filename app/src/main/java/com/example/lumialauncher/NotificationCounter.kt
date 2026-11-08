package com.example.lumialauncher

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Cuenta las notificaciones activas de correo y mensajes para mostrar
 * el número real en los mosaicos. Requiere que el usuario dé
 * "Acceso a notificaciones" a Lumia Launcher.
 */
class NotificationCounter : NotificationListenerService() {

    companion object {
        @Volatile var mail = 0
        @Volatile var msgs = 0
        @Volatile var onChange: (() -> Unit)? = null

        private val main = Handler(Looper.getMainLooper())

        private val MAIL_PKGS = setOf(
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.yahoo.mobile.client.android.mail"
        )
        private val MSG_PKGS = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )
    }

    override fun onListenerConnected() = recount()

    override fun onListenerDisconnected() {
        mail = 0; msgs = 0
        main.post { onChange?.invoke() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()

    private fun recount() {
        val list: Array<StatusBarNotification> = try {
            activeNotifications ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        var m = 0
        var s = 0
        for (sbn in list) {
            val n = sbn.notification
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) continue
            val pkg = sbn.packageName
            when {
                n.category == Notification.CATEGORY_EMAIL || pkg in MAIL_PKGS -> m++
                n.category == Notification.CATEGORY_MESSAGE || pkg in MSG_PKGS -> s++
            }
        }
        if (m != mail || s != msgs) {
            mail = m; msgs = s
            main.post { onChange?.invoke() }
        }
    }
}
