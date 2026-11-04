package org.opencar.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Mantiene viva la musica guardada mientras suena (aviso silencioso de reproduccion). */
class MusicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val t = LocalPlayer.current
        val title = t?.title ?: "Reproductor"
        val text = t?.artist ?: "Música del almacenamiento"
        val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel("music", "Reproductor", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "music").setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play).build()
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(2, n)
        } catch (e: Exception) { }
        return START_NOT_STICKY
    }
}
