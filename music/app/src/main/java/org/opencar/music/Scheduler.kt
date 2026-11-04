package org.opencar.music

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PersistableBundle

/** Programa la sincronizacion y las caratulas para cuando haya red. */
object Scheduler {
    private const val NET_ACTION = "org.opencar.music.NET"

    fun setup(c: Context) {
        try {
            val js = c.getSystemService(JobScheduler::class.java)
            val metered = Sync.prefs(c).getBoolean("metered", false)
            val net = if (metered) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED
            val cn = ComponentName(c, SyncJob::class.java)
            // cada hora, solo con red: sincroniza y busca caratulas
            js.schedule(JobInfo.Builder(1, cn).setRequiredNetworkType(net).setPeriodic(3_600_000L).setPersisted(true).build())
            // cada hora con cualquier red: solo caratulas (consume muy poco)
            js.schedule(JobInfo.Builder(4, cn).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(3_600_000L).setPersisted(true)
                .setExtras(PersistableBundle().apply { putInt("covers", 1) }).build())
        } catch (e: Exception) { }
        registerNet(c)
    }

    /** Pide ejecutar ahora (en cuanto haya red). */
    fun syncSoon(c: Context) {
        try {
            val js = c.getSystemService(JobScheduler::class.java)
            val metered = Sync.prefs(c).getBoolean("metered", false)
            val net = if (metered) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED
            val cn = ComponentName(c, SyncJob::class.java)
            js.schedule(JobInfo.Builder(2, cn).setRequiredNetworkType(net).setMinimumLatency(0).build())
            js.schedule(JobInfo.Builder(5, cn).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0)
                .setExtras(PersistableBundle().apply { putInt("covers", 1) }).build())
        } catch (e: Exception) { }
    }

    /** Aviso del sistema cada vez que aparece una red (funciona aunque la app no este abierta). */
    private fun registerNet(c: Context) {
        try {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            val pi = PendingIntent.getBroadcast(c, 7, Intent(c, NetReceiver::class.java).setAction(NET_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            cm.registerNetworkCallback(req, pi)
        } catch (e: Exception) { }
    }
}

class NetReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(n) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        val sp = Sync.prefs(c)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("last_net", 0L) < 600_000L) return   // no mas de una vez cada 10 min
        sp.edit().putLong("last_net", now).apply()
        Scheduler.syncSoon(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Scheduler.setup(c) }
}

class SyncJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        val coversOnly = p.extras.getInt("covers", 0) == 1
        Thread {
            try {
                if (!coversOnly && Sync.prefs(this).getBoolean("enabled", true)) Sync.run(this, false)
                Covers.scanLibrary(this)
                Lyrics.indexLibrary(this)
            } catch (e: Exception) { }
            jobFinished(p, false)
        }.start()
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean {
        Sync.cancel = true
        Covers.cancel = true
        return true
    }
}
