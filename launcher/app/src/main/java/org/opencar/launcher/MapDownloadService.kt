package org.opencar.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/** Baja el mapa y los datos de rutas aunque salgas de la pantalla. */
class MapDownloadService : Service() {
    private var thread: Thread? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { MapData.cancel = true; return START_NOT_STICKY }
        val force = intent?.getBooleanExtra("force", false) ?: false
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("maps", "Descarga de mapas", NotificationManager.IMPORTANCE_LOW))
        val n = build("Preparando…", 0)
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(77, n)
        if (thread?.isAlive == true) return START_NOT_STICKY
        wl = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencar:maps").also { it.acquire(3 * 60 * 60 * 1000L) }
        thread = Thread {
            val watcher = Thread {
                try {
                    while (true) { nm.notify(77, build(MapData.msg, MapData.pct())); Thread.sleep(1000) }
                } catch (e: InterruptedException) { }
            }.also { it.isDaemon = true; it.start() }
            MapData.downloadAll(applicationContext, force)
            watcher.interrupt()
            nm.notify(77, build(MapData.msg, 100))
            try { wl?.release() } catch (e: Exception) { }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun build(text: String, pct: Int): Notification =
        Notification.Builder(this, "maps").setSmallIcon(R.drawable.ic_place).setContentTitle("Mapas sin internet · $pct %")
            .setContentText(text).setOngoing(pct in 0..99).setProgress(100, pct, false).build()
}
