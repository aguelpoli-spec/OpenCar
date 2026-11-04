package org.opencar.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Permite al launcher leer la musica que suena. Ademas de las sesiones multimedia,
 * guarda el titulo/artista del aviso de reproduccion de la app de musica
 * (respaldo para radios cuya app de Bluetooth no publica la sesion completa).
 * El usuario lo activa una vez en Ajustes > Acceso a notificaciones.
 */
class MediaListener : NotificationListenerService() {

    companion object {
        @Volatile var title: String? = null
        @Volatile var text: String? = null
        @Volatile var pkg: String? = null
        @Volatile var ts = 0L
    }

    private fun isMedia(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        return n.category == Notification.CATEGORY_TRANSPORT ||
            n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
    }

    private fun grab(sbn: StatusBarNotification) {
        if (!isMedia(sbn)) return
        val ex = sbn.notification?.extras ?: return
        val t = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (t.isNullOrBlank()) return
        title = t
        text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()
        pkg = sbn.packageName
        ts = System.currentTimeMillis()
    }

    override fun onListenerConnected() {
        try { activeNotifications?.forEach { grab(it) } } catch (e: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) grab(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && sbn.packageName == pkg && isMedia(sbn)) {
            title = null; text = null; pkg = null
        }
    }
}
