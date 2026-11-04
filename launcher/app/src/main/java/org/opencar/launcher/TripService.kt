package org.opencar.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import kotlin.math.min

/**
 * Mide los kilometros recorridos con el GPS de la pantalla y los acumula
 * en las preferencias del launcher ("gps_total_m", en metros).
 * El odometro mostrado = valor que pone el usuario + lo recorrido desde entonces.
 */
class TripService : Service(), LocationListener {

    companion object {
        @Volatile var running = false
        @Volatile var lastFixMs = 0L
        @Volatile var speedMps = 0f
        private const val CHANNEL = "trip"
        @Volatile var bearing = -1f
        @Volatile var altitude = Double.NaN
        @Volatile var accM = -1f
        @Volatile var lat = Double.NaN
        @Volatile var lon = Double.NaN
        @Volatile var tripOn = false
        @Volatile var tripStartMs = 0L
        @Volatile var tripEndMs = 0L
        @Volatile var tripM = 0.0
        @Volatile var tripMoveMs = 0L
        @Volatile var tripMaxKmh = 0f
        private val hs = FloatArray(40)
        private val ha = FloatArray(40)
        private var hn = 0

        @Synchronized fun pushHist(s: Float, a: Float) {
            if (hn < 40) { hs[hn] = s; ha[hn] = a; hn++ }
            else { System.arraycopy(hs, 1, hs, 0, 39); System.arraycopy(ha, 1, ha, 0, 39); hs[39] = s; ha[39] = a }
        }
        @Synchronized fun hist(): Pair<FloatArray, FloatArray> = Pair(hs.copyOf(hn), ha.copyOf(hn))

        fun loadTrip(p: SharedPreferences) {
            tripOn = p.getBoolean("trip_on", false)
            tripStartMs = p.getLong("trip_start", 0L)
            tripEndMs = p.getLong("trip_end", 0L)
            tripM = p.getLong("trip_m", 0L).toDouble()
            tripMoveMs = p.getLong("trip_move_ms", 0L)
            tripMaxKmh = p.getFloat("trip_max", 0f)
        }

        fun saveTrip(p: SharedPreferences) {
            p.edit().putBoolean("trip_on", tripOn).putLong("trip_start", tripStartMs).putLong("trip_end", tripEndMs)
                .putLong("trip_m", tripM.toLong()).putLong("trip_move_ms", tripMoveMs).putFloat("trip_max", tripMaxKmh).apply()
        }

        fun setTrip(p: SharedPreferences, on: Boolean) {
            if (on) { tripOn = true; tripStartMs = System.currentTimeMillis(); tripEndMs = 0L; tripM = 0.0; tripMoveMs = 0L; tripMaxKmh = 0f }
            else { tripOn = false; tripEndMs = System.currentTimeMillis() }
            saveTrip(p)
        }
    }

    private lateinit var prefs: SharedPreferences
    private var last: Location? = null
    private var pendingM = 0f
    private var slow = false
    private var lastTripT = 0L
    private var lastHistT = 0L
    private var lastSaveT = 0L
    private var lastMove = SystemClock.elapsedRealtime()

    private fun setInterval(ms: Long) {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(this)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, ms, 0f, this)
            slow = ms > 1000L
        } catch (e: Exception) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        loadTrip(prefs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!running) startUpdates()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Odómetro", NotificationManager.IMPORTANCE_MIN)
            )
            Notification.Builder(this, CHANNEL)
                .setContentTitle("OpenCar Launcher")
                .setContentText("Midiendo kilómetros con el GPS")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("OpenCar Launcher")
                .setContentText("Midiendo kilómetros con el GPS")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build()
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            // Si el sistema no deja iniciarlo en primer plano, seguimos igual
        }
    }

    private fun startUpdates() {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            running = true
        } catch (e: SecurityException) {
            stopSelf()
        } catch (e: Exception) {
            // GPS no disponible en esta pantalla
        }
    }

    override fun onLocationChanged(loc: Location) {
        lastFixMs = System.currentTimeMillis()
        val prev = last
        last = loc
        // velocidad instantanea para el velocimetro del inicio
        speedMps = if (loc.hasSpeed()) loc.speed else if (prev != null) {
            val dts = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000f
            if (dts > 0f) prev.distanceTo(loc) / dts else 0f
        } else 0f
        bearing = if (loc.hasBearing() && speedMps > 1f) loc.bearing else -1f
        altitude = if (loc.hasAltitude()) loc.altitude else Double.NaN
        accM = if (loc.hasAccuracy()) loc.accuracy else -1f
        lat = loc.latitude; lon = loc.longitude
        try { BgWatch.onFix(applicationContext, prefs, loc.latitude, loc.longitude, speedMps * 3.6f) } catch (e: Throwable) { }
        val tn = SystemClock.elapsedRealtime()
        if (tripOn) {
            val dt = if (lastTripT == 0L) 0f else min(10f, (tn - lastTripT) / 1000f)
            if (speedMps >= 0.6f && dt > 0f) {
                tripMoveMs += (dt * 1000).toLong(); tripM += speedMps * dt
                val k = speedMps * 3.6f
                if (k < 250f && k > tripMaxKmh) tripMaxKmh = k
            }
            if (tn - lastSaveT > 15_000L) { lastSaveT = tn; saveTrip(prefs) }
        }
        lastTripT = tn
        if (tn - lastHistT >= 3000L) {
            lastHistT = tn
            val kk = speedMps * 3.6f
            pushHist(if (kk < 3f) 0f else kk, if (loc.hasAltitude()) loc.altitude.toFloat() else 0f)
        }
        val tNow = SystemClock.elapsedRealtime()
        if (speedMps > 2f) { lastMove = tNow; if (slow) setInterval(1000L) }
        else if (!slow && tNow - lastMove > 120_000L) setInterval(5000L)
        if (prev == null) return
        if (loc.hasAccuracy() && loc.accuracy > 25f) return
        val dt = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000f
        if (dt <= 0f) return
        val d = prev.distanceTo(loc)
        val v = d / dt
        if (v < 1.0f || v > 60f) return // quieto (ruido del GPS) o salto imposible
        pendingM += d
        if (pendingM >= 100f) flush()
    }

    private fun flush() {
        val whole = pendingM.toLong()
        if (whole <= 0L) return
        pendingM -= whole
        prefs.edit().putLong("gps_total_m", prefs.getLong("gps_total_m", 0L) + whole).apply()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        flush()
        try {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        } catch (e: Exception) {
        }
        running = false
        super.onDestroy()
    }
}
