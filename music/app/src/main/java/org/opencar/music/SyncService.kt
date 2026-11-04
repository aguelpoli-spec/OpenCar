package org.opencar.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Sincroniza a mano ("Sincronizar ahora") con un aviso visible mientras copia. */
class SyncService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    private fun note(text: String): Notification =
        Notification.Builder(this, "sync").setContentTitle("OpenCar Music").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("sync", "Sincronización", NotificationManager.IMPORTANCE_LOW))
        try { startForeground(3, note("Sincronizando tu música…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) } catch (e: Exception) { }
        Thread {
            val ticker = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val t = if (Sync.running) "Copiadas ${Sync.done} de ${Sync.found}" + (if (Sync.current.isNotEmpty()) " · ${Sync.current}" else "") else "Buscando carátulas…"
                        nm.notify(3, note(t))
                        Thread.sleep(2000)
                    }
                } catch (e: InterruptedException) { }
            }
            ticker.start()
            try {
                Sync.run(this, true)
                Covers.scanLibrary(this)
                Lyrics.indexLibrary(this)
            } catch (e: Exception) { }
            ticker.interrupt()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }
}
